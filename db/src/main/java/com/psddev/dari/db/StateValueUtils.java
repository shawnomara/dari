package com.psddev.dari.db;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.psddev.dari.util.DateUtils;
import com.psddev.dari.util.ObjectMap;
import com.psddev.dari.util.ObjectUtils;
import com.psddev.dari.util.StorageItem;
import com.psddev.dari.util.StringUtils;

/** State value utility methods. */
abstract class StateValueUtils {

    /** Key for the embedded object's unique ID. */
    public static final String ID_KEY = "_id";

    /** Key for the embedded object's type. */
    public static final String TYPE_KEY = "_type";

    /**
     * Key for the reference to the object that should replace the
     * embedded object map.
     */
    public static final String REFERENCE_KEY = "_ref";

    /**
     * Thread local map used for detecting circular references in
     * {@link #resolveReferences}.
     */
    private static final ThreadLocal<Map<UUID, Object>> CIRCULAR_REFERENCES = new ThreadLocal<Map<UUID, Object>>();

    private static final Logger LOGGER = LoggerFactory.getLogger(StateValueUtils.class);

    /** Converts the given {@code object} into an ID if it's a reference. */
    public static UUID toIdIfReference(Object object) {
        return object instanceof Map ?
                ObjectUtils.to(UUID.class, ((Map<?, ?>) object).get(REFERENCE_KEY)) :
                null;
    }

    public static Object toObjectIfReference(Database database, Object object) {
        if (object instanceof Map) {
            Map<?, ?> objectMap = (Map<?, ?>) object;
            UUID id = ObjectUtils.to(UUID.class, objectMap.get(REFERENCE_KEY));

            if (id != null) {
                UUID typeId = ObjectUtils.to(UUID.class, objectMap.get(TYPE_KEY));
                ObjectType type = database.getEnvironment().getTypeById(typeId);
                if (type == null || type.isAbstract()) {
                    return database.readFirst(Query.from(Object.class).where("_id = ?", id));
                }

                Object reference = type.createObject(id);
                State referenceState = State.getInstance(reference);
                referenceState.setStatus(StateStatus.REFERENCE_ONLY);
                referenceState.setResolveToReferenceOnly(true);
                return reference;
            }
        }

        return null;
    }

    /** Resolves all object references within the given {@code items}. */
    public static Map<UUID, Object> resolveReferences(Database database, Object parent, Iterable<?> items) {
        State parentState = State.getInstance(parent);

        if (parentState != null && parentState.isResolveToReferenceOnly()) {
            Map<UUID, Object> references = new HashMap<UUID, Object>();
            for (Object item : items) {
                Object itemReference = toObjectIfReference(database, item);
                if (itemReference != null) {
                    references.put(State.getInstance(itemReference).getId(), itemReference);
                }
            }
            return references;
        }

        if (parent instanceof Modification) {
            for (Object item : parentState.getObjects()) {
                if (!(item instanceof Modification)) {
                    parent = item;
                    break;
                }
            }
            if (parent instanceof Modification) {
                parent = null;
            }
        }

        boolean isFirst = false;
        try {
            Map<UUID, Object> circularReferences = CIRCULAR_REFERENCES.get();
            if (circularReferences == null) {
                isFirst = true;
                circularReferences = new HashMap<UUID, Object>();
                CIRCULAR_REFERENCES.set(circularReferences);
            }

            if (parentState != null) {
                circularReferences.put(parentState.getId(), parent);
            }

            // Find IDs that have not been resolved yet.
            Map<UUID, Object> references = new HashMap<UUID, Object>();
            Set<UUID> unresolvedIds = new HashSet<UUID>();
            Set<UUID> unresolvedTypeIds = new HashSet<UUID>();
            for (Object item : items) {
                UUID id = toIdIfReference(item);
                if (id != null) {
                    if (circularReferences.containsKey(id)) {
                        references.put(id, circularReferences.get(id));
                    } else {
                        unresolvedIds.add(id);
                        unresolvedTypeIds.add(ObjectUtils.to(UUID.class, ((Map<?, ?>) item).get(TYPE_KEY)));
                    }
                }
            }

            // Fetch unresolved objects and cache them.
            if (!unresolvedIds.isEmpty()) {
                for (Object object : Query.
                        from(Object.class).
                        where("_id = ?", unresolvedIds).
                        using(database).
                        option(State.REFERENCE_RESOLVING_QUERY_OPTION, parent).
                        option(State.UNRESOLVED_TYPE_IDS_QUERY_OPTION, unresolvedTypeIds).
                        selectAll()) {
                    UUID id = State.getInstance(object).getId();
                    unresolvedIds.remove(id);
                    circularReferences.put(id, object);
                    references.put(id, object);
                }
                for (UUID id : unresolvedIds) {
                    circularReferences.put(id, null);
                }
            }

            return references;

        } finally {
            if (isFirst) {
                CIRCULAR_REFERENCES.remove();
            }
        }
    }

    /**
     * Converts the given {@code value} to an instance of the type that
     * matches the given {@code field} and {@code type} and is most
     * commonly used in Java.
     */
    public static Object toJavaValue(
            Database database,
            Object object,
            ObjectField field,
            String type,
            Object value) {

        if (value == null) {
            return null;
        }

        UUID valueId = toIdIfReference(value);
        if (valueId != null) {
            Map<UUID, Object> references = resolveReferences(database, object, Collections.singleton(value));
            value = references.get(valueId);
            if (value == null) {
                return null;
            }
        }

        if (field == null || type == null) {
            return value;
        }

        int slashAt = type.indexOf('/');
        String firstType;
        String subType;

        if (slashAt > -1) {
            firstType = type.substring(0, slashAt);
            subType = type.substring(slashAt + 1);

        } else {
            firstType = type;
            subType = null;
        }

        Converter converter = CONVERTERS.get(firstType);
        if (converter == null) {
            return value;
        }

        try {
            return converter.toJavaValue(database, object, field, subType, value);

        } catch (Exception ex) {
            if (LOGGER.isDebugEnabled()) {
                Class<?> valueClass = value != null ? value.getClass() : null;
                LOGGER.debug(String.format(
                        "Can't convert [%s] of [%s] to [%s]!",
                        value, valueClass, type), ex);
            }

            if (object != null) {
                State.getInstance(object).putValue("dari.trash." + field.getInternalName(), value);
            }
            return null;
        }
    }

    /**
     * Interface that defines how to convert between various
     * representations of a state value.
     */
    private interface Converter {
        Object toJavaValue(
                Database database,
                Object object,
                ObjectField field,
                String subType,
                Object value)
                throws Exception;
    }

    private static final Map<String, Converter> CONVERTERS; static {
        Map<String, Converter> m = new HashMap<String, Converter>();

        m.put(ObjectField.DATE_TYPE, new Converter() {
            @Override
            public Object toJavaValue(
                    Database database,
                    Object object,
                    ObjectField field,
                    String subType,
                    Object value) {

                if (value instanceof Date) {
                    return value;

                } else if (value instanceof Number) {
                    return new Date(((Number) value).longValue());

                } else {
                    return DateUtils.fromString(value.toString());
                }
            }
        });

        m.put(ObjectField.FILE_TYPE, new Converter() {
            @Override
            public Object toJavaValue(
                    Database database,
                    Object object,
                    ObjectField field,
                    String subType,
                    Object value) {

                if (value instanceof StorageItem) {
                    return value;

                } else if (value instanceof String) {
                    return StorageItem.Static.createUrl((String) value);

                } else if (value instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) value;
                    StorageItem item = StorageItem.Static.createIn(ObjectUtils.to(String.class, map.get("storage")));
                    new ObjectMap(item).putAll(map);
                    return item;

                } else {
                    throw new IllegalArgumentException();
                }
            }
        });

        m.put(ObjectField.LIST_TYPE, new Converter() {
            @Override
            public Object toJavaValue(
                    Database database,
                    Object object,
                    ObjectField field,
                    String subType,
                    Object value) {

                if (value instanceof StateValueList) {
                    return value;

                } else {
                    Iterable<?> iterable = ObjectUtils.to(Iterable.class, value);
                    return new StateValueList(database, object, field, subType, iterable);
                }
            }
        });

        m.put(ObjectField.LOCATION_TYPE, new Converter() {
            @Override
            public Object toJavaValue(
                    Database database,
                    Object object,
                    ObjectField field,
                    String subType,
                    Object value) {

                if (value instanceof Location) {
                    return value;

                } else if (value instanceof Map) {
                    Map<?, ?> map = (Map<?, ?>) value;
                    Double x = ObjectUtils.to(Double.class, map.get("x"));
                    Double y = ObjectUtils.to(Double.class, map.get("y"));
                    if (x != null && y != null) {
                        return new Location(x, y);
                    }
                }

                throw new IllegalArgumentException();
            }
        });

        m.put(ObjectField.MAP_TYPE, new Converter() {
            @Override
            public Object toJavaValue(
                    Database database,
                    Object object,
                    ObjectField field,
                    String subType,
                    Object value) {

                if (value instanceof StateValueMap) {
                    return value;

                } else if (value instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) value;
                    return new StateValueMap(database, object, field, subType, map);

                } else {
                    throw new IllegalArgumentException();
                }
            }
        });

        m.put(ObjectField.RECORD_TYPE, new Converter() {
            @Override
            public Object toJavaValue(
                    Database database,
                    Object object,
                    ObjectField field,
                    String subType,
                    Object value) {

                if (value instanceof Recordable) {
                    return value;

                } else if (value instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> valueMap = (Map<String, Object>) value;
                    Object typeId = valueMap.get(TYPE_KEY);

                    if (typeId != null) {
                        State objectState = State.getInstance(object);
                        DatabaseEnvironment environment = objectState.getDatabase().getEnvironment();
                        ObjectType valueType = environment.getTypeById(ObjectUtils.to(UUID.class, typeId));

                        if (valueType == null) {
                            valueType = environment.getTypeByName(ObjectUtils.to(String.class, typeId));
                        }

                        if (valueType != null) {
                            value = valueType.createObject(ObjectUtils.to(UUID.class, valueMap.get(ID_KEY)));
                            State valueState = State.getInstance(value);

                            valueState.setDatabase(database);
                            valueState.setResolveToReferenceOnly(objectState.isResolveToReferenceOnly());
                            valueState.putAll(valueMap);

                            return value;
                        }
                    }

                } else {
                    UUID id = ObjectUtils.to(UUID.class, value);
                    if (id != null) {
                        return Query.findById(Object.class, id);
                    }
                }

                throw new IllegalArgumentException();
            }
        });

        m.put(ObjectField.REFERENTIAL_TEXT_TYPE, new Converter() {
            @Override
            public Object toJavaValue(
                    Database database,
                    Object object,
                    ObjectField field,
                    String subType,
                    Object value) {

                if (value instanceof ReferentialText) {
                    return value;

                } else {
                    ReferentialText text = new ReferentialText();
                    if (value instanceof Iterable) {
                        for (Object item : (Iterable<?>) value) {
                            text.add(item);
                        }
                    } else {
                        text.add(value.toString());
                    }
                    return text;
                }
            }
        });

        m.put(ObjectField.SET_TYPE, new Converter() {
            @Override
            public Object toJavaValue(
                    Database database,
                    Object object,
                    ObjectField field,
                    String subType,
                    Object value) {

                if (value instanceof StateValueSet) {
                    return value;

                } else {
                    Iterable<?> iterable = ObjectUtils.to(Iterable.class, value);
                    return new StateValueSet(database, object, field, subType, iterable);
                }
            }
        });

        m.put(ObjectField.TEXT_TYPE, new Converter() {
            @Override
            public Object toJavaValue(
                    Database database,
                    Object object,
                    ObjectField field,
                    String subType,
                    Object value) {

                if (value instanceof byte[]) {
                    value = new String((byte[]) value, StringUtils.UTF_8);
                }

                String enumClassName = field.getJavaEnumClassName();
                Class<?> enumClass = ObjectUtils.getClassByName(enumClassName);
                if (enumClass != null && Enum.class.isAssignableFrom(enumClass)) {
                    return ObjectUtils.to(enumClass, value);
                }

                return value.toString();
            }
        });

        m.put(ObjectField.URI_TYPE, new Converter() {
            @Override
            public Object toJavaValue(
                    Database database,
                    Object object,
                    ObjectField field,
                    String subType,
                    Object value)
                    throws URISyntaxException {

                if (value instanceof URI) {
                    return value;

                } else {
                    return new URI(value.toString());
                }
            }
        });

        m.put(ObjectField.URL_TYPE, new Converter() {
            @Override
            public Object toJavaValue(
                    Database database,
                    Object object,
                    ObjectField field,
                    String subType,
                    Object value)
                    throws MalformedURLException {

                if (value instanceof URL) {
                    return value;

                } else {
                    return new URL(value.toString());
                }
            }
        });

        m.put(ObjectField.UUID_TYPE, new Converter() {
            @Override
            public Object toJavaValue(
                    Database database,
                    Object object,
                    ObjectField field,
                    String subType,
                    Object value) {

                if (value instanceof UUID) {
                    return value;

                } else {
                    UUID uuid = ObjectUtils.to(UUID.class, value);
                    if (uuid != null) {
                        return uuid;
                    }
                }

                throw new IllegalArgumentException();
            }
        });

        CONVERTERS = m;
    }
}
