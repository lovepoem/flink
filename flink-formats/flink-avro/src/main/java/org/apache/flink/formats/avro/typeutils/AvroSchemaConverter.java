/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.formats.avro.typeutils;

import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.api.java.typeutils.RowTypeInfo;
import org.apache.flink.formats.avro.AvroRowDataDeserializationSchema;
import org.apache.flink.formats.avro.AvroRowDataSerializationSchema;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.legacy.types.logical.TypeInformationRawType;
import org.apache.flink.table.types.AtomicDataType;
import org.apache.flink.table.types.DataType;
import org.apache.flink.table.types.logical.ArrayType;
import org.apache.flink.table.types.logical.DecimalType;
import org.apache.flink.table.types.logical.IntType;
import org.apache.flink.table.types.logical.LocalZonedTimestampType;
import org.apache.flink.table.types.logical.LogicalType;
import org.apache.flink.table.types.logical.LogicalTypeFamily;
import org.apache.flink.table.types.logical.MapType;
import org.apache.flink.table.types.logical.MultisetType;
import org.apache.flink.table.types.logical.RowType;
import org.apache.flink.table.types.logical.TimeType;
import org.apache.flink.table.types.logical.TimestampType;
import org.apache.flink.types.Row;
import org.apache.flink.util.Preconditions;

import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.SchemaParseException;
import org.apache.avro.specific.SpecificData;
import org.apache.avro.specific.SpecificRecord;

import java.util.List;

/**
 * Converts an Avro schema into Flink's type information. It uses {@link RowTypeInfo} for
 * representing objects and converts Avro types into types that are compatible with Flink's Table &
 * SQL API.
 *
 * <p>Note: Changes in this class need to be kept in sync with the corresponding runtime classes
 * {@link AvroRowDataDeserializationSchema} and {@link AvroRowDataSerializationSchema}.
 */
public class AvroSchemaConverter {

    private AvroSchemaConverter() {
        // private
    }

    /**
     * Converts an Avro class into a nested row structure with deterministic field order and data
     * types that are compatible with Flink's Table & SQL API.
     *
     * @param avroClass Avro specific record that contains schema information
     * @return type information matching the schema
     */
    @SuppressWarnings("unchecked")
    public static <T extends SpecificRecord> TypeInformation<Row> convertToTypeInfo(
            Class<T> avroClass) {
        return convertToTypeInfo(avroClass, true);
    }

    /**
     * Converts an Avro class into a nested row structure with deterministic field order and data
     * types that are compatible with Flink's Table & SQL API.
     *
     * @param avroClass Avro specific record that contains schema information
     * @param legacyTimestampMapping legacy mapping of timestamp types
     * @return type information matching the schema
     */
    @SuppressWarnings("unchecked")
    public static <T extends SpecificRecord> TypeInformation<Row> convertToTypeInfo(
            Class<T> avroClass, boolean legacyTimestampMapping) {
        Preconditions.checkNotNull(avroClass, "Avro specific record class must not be null.");
        // determine schema to retrieve deterministic field order
        final Schema schema = SpecificData.get().getSchema(avroClass);
        return (TypeInformation<Row>) convertToTypeInfo(schema, true);
    }

    /**
     * Converts an Avro schema string into a nested row structure with deterministic field order and
     * data types that are compatible with Flink's Table & SQL API.
     *
     * @param avroSchemaString Avro schema definition string
     * @return type information matching the schema
     */
    @SuppressWarnings("unchecked")
    public static <T> TypeInformation<T> convertToTypeInfo(String avroSchemaString) {
        return convertToTypeInfo(avroSchemaString, true);
    }

    /**
     * Converts an Avro schema string into a nested row structure with deterministic field order and
     * data types that are compatible with Flink's Table & SQL API.
     *
     * @param avroSchemaString Avro schema definition string
     * @param legacyTimestampMapping legacy mapping of timestamp types
     * @return type information matching the schema
     */
    @SuppressWarnings("unchecked")
    public static <T> TypeInformation<T> convertToTypeInfo(
            String avroSchemaString, boolean legacyTimestampMapping) {
        Preconditions.checkNotNull(avroSchemaString, "Avro schema must not be null.");
        final Schema schema;
        try {
            schema = new Schema.Parser().parse(avroSchemaString);
        } catch (SchemaParseException e) {
            throw new IllegalArgumentException("Could not parse Avro schema string.", e);
        }
        return (TypeInformation<T>) convertToTypeInfo(schema, legacyTimestampMapping);
    }

    private static TypeInformation<?> convertToTypeInfo(
            Schema schema, boolean legacyTimestampMapping) {
        switch (schema.getType()) {
            case RECORD:
                final List<Schema.Field> fields = schema.getFields();

                final TypeInformation<?>[] types = new TypeInformation<?>[fields.size()];
                final String[] names = new String[fields.size()];
                for (int i = 0; i < fields.size(); i++) {
                    final Schema.Field field = fields.get(i);
                    types[i] = convertToTypeInfo(field.schema(), legacyTimestampMapping);
                    names[i] = field.name();
                }
                return Types.ROW_NAMED(names, types);
            case ENUM:
                return Types.STRING;
            case ARRAY:
                // result type might either be ObjectArrayTypeInfo or BasicArrayTypeInfo for Strings
                return Types.OBJECT_ARRAY(
                        convertToTypeInfo(schema.getElementType(), legacyTimestampMapping));
            case MAP:
                return Types.MAP(
                        Types.STRING,
                        convertToTypeInfo(schema.getValueType(), legacyTimestampMapping));
            case UNION:
                final Schema actualSchema;
                if (schema.getTypes().size() == 2
                        && schema.getTypes().get(0).getType() == Schema.Type.NULL) {
                    actualSchema = schema.getTypes().get(1);
                } else if (schema.getTypes().size() == 2
                        && schema.getTypes().get(1).getType() == Schema.Type.NULL) {
                    actualSchema = schema.getTypes().get(0);
                } else if (schema.getTypes().size() == 1) {
                    actualSchema = schema.getTypes().get(0);
                } else {
                    // use Kryo for serialization
                    return Types.GENERIC(Object.class);
                }
                return convertToTypeInfo(actualSchema, legacyTimestampMapping);
            case FIXED:
                // logical decimal type
                if (schema.getLogicalType() instanceof LogicalTypes.Decimal) {
                    return Types.BIG_DEC;
                }
                // convert fixed size binary data to primitive byte arrays
                return Types.PRIMITIVE_ARRAY(Types.BYTE);
            case STRING:
                // convert Avro's Utf8/CharSequence to String
                return Types.STRING;
            case BYTES:
                // logical decimal type
                if (schema.getLogicalType() instanceof LogicalTypes.Decimal) {
                    return Types.BIG_DEC;
                }
                return Types.PRIMITIVE_ARRAY(Types.BYTE);
            case INT:
                // logical date and time type
                final org.apache.avro.LogicalType logicalType = schema.getLogicalType();
                if (logicalType == LogicalTypes.date()) {
                    return Types.SQL_DATE;
                } else if (logicalType == LogicalTypes.timeMillis()) {
                    return Types.SQL_TIME;
                }
                return Types.INT;
            case LONG:
                if (legacyTimestampMapping) {
                    if (schema.getLogicalType() == LogicalTypes.timestampMillis()
                            || schema.getLogicalType() == LogicalTypes.timestampMicros()) {
                        return Types.SQL_TIMESTAMP;
                    } else if (schema.getLogicalType() == LogicalTypes.timeMicros()
                            || schema.getLogicalType() == LogicalTypes.timeMillis()) {
                        return Types.SQL_TIME;
                    }
                } else {
                    // Avro logical timestamp types to Flink DataStream timestamp types
                    if (schema.getLogicalType() == LogicalTypes.timestampMillis()
                            || schema.getLogicalType() == LogicalTypes.timestampMicros()) {
                        return Types.INSTANT;
                    } else if (schema.getLogicalType() == LogicalTypes.localTimestampMillis()
                            || schema.getLogicalType() == LogicalTypes.localTimestampMicros()) {
                        return Types.LOCAL_DATE_TIME;
                    } else if (schema.getLogicalType() == LogicalTypes.timeMicros()
                            || schema.getLogicalType() == LogicalTypes.timeMillis()) {
                        return Types.SQL_TIME;
                    }
                }
                return Types.LONG;
            case FLOAT:
                return Types.FLOAT;
            case DOUBLE:
                return Types.DOUBLE;
            case BOOLEAN:
                return Types.BOOLEAN;
            case NULL:
                return Types.VOID;
        }
        throw new IllegalArgumentException("Unsupported Avro type '" + schema.getType() + "'.");
    }

    /**
     * Converts an Avro schema string into a nested row structure with deterministic field order and
     * data types that are compatible with Flink's Table & SQL API.
     *
     * @param avroSchemaString Avro schema definition string
     * @return data type matching the schema
     */
    public static DataType convertToDataType(String avroSchemaString) {
        return convertToDataType(avroSchemaString, true);
    }

    /**
     * Converts an Avro schema string into a nested row structure with deterministic field order and
     * data types that are compatible with Flink's Table & SQL API.
     *
     * @param avroSchemaString Avro schema definition string
     * @param legacyTimestampMapping legacy mapping of local timestamps
     * @return data type matching the schema
     */
    public static DataType convertToDataType(
            String avroSchemaString, boolean legacyTimestampMapping) {
        Preconditions.checkNotNull(avroSchemaString, "Avro schema must not be null.");
        final Schema schema;
        try {
            schema = new Schema.Parser().parse(avroSchemaString);
        } catch (SchemaParseException e) {
            throw new IllegalArgumentException("Could not parse Avro schema string.", e);
        }
        return convertToDataType(schema, legacyTimestampMapping);
    }

    private static DataType convertToDataType(Schema schema, boolean legacyMapping) {
        switch (schema.getType()) {
            case RECORD:
                final List<Schema.Field> schemaFields = schema.getFields();

                final DataTypes.Field[] fields = new DataTypes.Field[schemaFields.size()];
                for (int i = 0; i < schemaFields.size(); i++) {
                    final Schema.Field field = schemaFields.get(i);
                    fields[i] =
                            DataTypes.FIELD(
                                    field.name(), convertToDataType(field.schema(), legacyMapping));
                }
                return DataTypes.ROW(fields).notNull();
            case ENUM:
                return DataTypes.STRING().notNull();
            case ARRAY:
                return DataTypes.ARRAY(convertToDataType(schema.getElementType(), legacyMapping))
                        .notNull();
            case MAP:
                return DataTypes.MAP(
                                DataTypes.STRING().notNull(),
                                convertToDataType(schema.getValueType(), legacyMapping))
                        .notNull();
            case UNION:
                final Schema actualSchema;
                final boolean nullable;
                if (schema.getTypes().size() == 2
                        && schema.getTypes().get(0).getType() == Schema.Type.NULL) {
                    actualSchema = schema.getTypes().get(1);
                    nullable = true;
                } else if (schema.getTypes().size() == 2
                        && schema.getTypes().get(1).getType() == Schema.Type.NULL) {
                    actualSchema = schema.getTypes().get(0);
                    nullable = true;
                } else if (schema.getTypes().size() == 1) {
                    actualSchema = schema.getTypes().get(0);
                    nullable = false;
                } else {
                    // use Kryo for serialization
                    return new AtomicDataType(
                            new TypeInformationRawType<>(false, Types.GENERIC(Object.class)));
                }
                DataType converted = convertToDataType(actualSchema, legacyMapping);
                return nullable ? converted.nullable() : converted;
            case FIXED:
                // logical decimal type
                if (schema.getLogicalType() instanceof LogicalTypes.Decimal) {
                    final LogicalTypes.Decimal decimalType =
                            (LogicalTypes.Decimal) schema.getLogicalType();
                    return DataTypes.DECIMAL(decimalType.getPrecision(), decimalType.getScale())
                            .notNull();
                }
                // convert fixed size binary data to primitive byte arrays
                return DataTypes.VARBINARY(schema.getFixedSize()).notNull();
            case STRING:
                // convert Avro's Utf8/CharSequence to String
                return DataTypes.STRING().notNull();
            case BYTES:
                // logical decimal type
                if (schema.getLogicalType() instanceof LogicalTypes.Decimal) {
                    final LogicalTypes.Decimal decimalType =
                            (LogicalTypes.Decimal) schema.getLogicalType();
                    return DataTypes.DECIMAL(decimalType.getPrecision(), decimalType.getScale())
                            .notNull();
                }
                return DataTypes.BYTES().notNull();
            case INT:
                // logical date and time type
                final org.apache.avro.LogicalType logicalType = schema.getLogicalType();
                if (logicalType == LogicalTypes.date()) {
                    return DataTypes.DATE().notNull();
                } else if (logicalType == LogicalTypes.timeMillis()) {
                    return DataTypes.TIME(3).notNull();
                }
                return DataTypes.INT().notNull();
            case LONG:
                if (legacyMapping) {
                    // Avro logical timestamp types to Flink SQL timestamp types
                    if (schema.getLogicalType() == LogicalTypes.timestampMillis()) {
                        return DataTypes.TIMESTAMP(3).notNull();
                    } else if (schema.getLogicalType() == LogicalTypes.timestampMicros()) {
                        return DataTypes.TIMESTAMP(6).notNull();
                    } else if (schema.getLogicalType() == LogicalTypes.timeMillis()) {
                        return DataTypes.TIME(3).notNull();
                    } else if (schema.getLogicalType() == LogicalTypes.timeMicros()) {
                        return DataTypes.TIME(6).notNull();
                    }
                } else {
                    // Avro logical timestamp types to Flink SQL timestamp types
                    if (schema.getLogicalType() == LogicalTypes.timestampMillis()) {
                        return DataTypes.TIMESTAMP_WITH_LOCAL_TIME_ZONE(3).notNull();
                    } else if (schema.getLogicalType() == LogicalTypes.timestampMicros()) {
                        return DataTypes.TIMESTAMP_WITH_LOCAL_TIME_ZONE(6).notNull();
                    } else if (schema.getLogicalType() == LogicalTypes.timeMillis()) {
                        return DataTypes.TIME(3).notNull();
                    } else if (schema.getLogicalType() == LogicalTypes.timeMicros()) {
                        return DataTypes.TIME(6).notNull();
                    } else if (schema.getLogicalType() == LogicalTypes.localTimestampMillis()) {
                        return DataTypes.TIMESTAMP(3).notNull();
                    } else if (schema.getLogicalType() == LogicalTypes.localTimestampMicros()) {
                        return DataTypes.TIMESTAMP(6).notNull();
                    }
                }

                return DataTypes.BIGINT().notNull();
            case FLOAT:
                return DataTypes.FLOAT().notNull();
            case DOUBLE:
                return DataTypes.DOUBLE().notNull();
            case BOOLEAN:
                return DataTypes.BOOLEAN().notNull();
            case NULL:
                return DataTypes.NULL();
        }
        throw new IllegalArgumentException("Unsupported Avro type '" + schema.getType() + "'.");
    }

    /**
     * Converts Flink SQL {@link LogicalType} (can be nested) into an Avro schema.
     *
     * <p>Use "org.apache.flink.avro.generated.record" as the type name.
     *
     * @param schema the schema type, usually it should be the top level record type, e.g. not a
     *     nested type
     * @return Avro's {@link Schema} matching this logical type.
     */
    public static Schema convertToSchema(LogicalType schema) {
        return convertToSchema(schema, true);
    }

    /**
     * Converts Flink SQL {@link LogicalType} (can be nested) into an Avro schema.
     *
     * <p>Use "org.apache.flink.avro.generated.record" as the type name.
     *
     * @param schema the schema type, usually it should be the top level record type, e.g. not a
     *     nested type
     * @param legacyTimestampMapping whether to use the legacy timestamp mapping
     * @return Avro's {@link Schema} matching this logical type.
     */
    public static Schema convertToSchema(LogicalType schema, boolean legacyTimestampMapping) {
        return convertToSchema(
                schema, "org.apache.flink.avro.generated.record", legacyTimestampMapping);
    }

    /**
     * Converts Flink SQL {@link LogicalType} (can be nested) into an Avro schema.
     *
     * <p>The "{rowName}_" is used as the nested row type name prefix in order to generate the right
     * schema. Nested record type that only differs with type name is still compatible.
     *
     * @param logicalType logical type
     * @param rowName the record name
     * @return Avro's {@link Schema} matching this logical type.
     */
    public static Schema convertToSchema(LogicalType logicalType, String rowName) {
        return convertToSchema(logicalType, rowName, true);
    }

    /**
     * Converts Flink SQL {@link LogicalType} (can be nested) into an Avro schema.
     *
     * <p>The "{rowName}_" is used as the nested row type name prefix in order to generate the right
     * schema. Nested record type that only differs with type name is still compatible.
     *
     * @param logicalType logical type
     * @param rowName the record name
     * @param legacyTimestampMapping whether to use legal timestamp mapping
     * @return Avro's {@link Schema} matching this logical type.
     */
    public static Schema convertToSchema(
            LogicalType logicalType, String rowName, boolean legacyTimestampMapping) {
        int precision;
        boolean nullable = logicalType.isNullable();
        switch (logicalType.getTypeRoot()) {
            case NULL:
                return SchemaBuilder.builder().nullType();
            case BOOLEAN:
                Schema bool = SchemaBuilder.builder().booleanType();
                return nullable ? nullableSchema(bool) : bool;
            case TINYINT:
            case SMALLINT:
            case INTEGER:
                Schema integer = SchemaBuilder.builder().intType();
                return nullable ? nullableSchema(integer) : integer;
            case BIGINT:
                Schema bigint = SchemaBuilder.builder().longType();
                return nullable ? nullableSchema(bigint) : bigint;
            case FLOAT:
                Schema f = SchemaBuilder.builder().floatType();
                return nullable ? nullableSchema(f) : f;
            case DOUBLE:
                Schema d = SchemaBuilder.builder().doubleType();
                return nullable ? nullableSchema(d) : d;
            case CHAR:
            case VARCHAR:
                Schema str = SchemaBuilder.builder().stringType();
                return nullable ? nullableSchema(str) : str;
            case BINARY:
            case VARBINARY:
                Schema binary = SchemaBuilder.builder().bytesType();
                return nullable ? nullableSchema(binary) : binary;
            case TIMESTAMP_WITHOUT_TIME_ZONE:
                // use long to represents Timestamp
                final TimestampType timestampType = (TimestampType) logicalType;
                precision = timestampType.getPrecision();
                org.apache.avro.LogicalType avroLogicalType;
                if (legacyTimestampMapping) {
                    if (precision <= 3) {
                        avroLogicalType = LogicalTypes.timestampMillis();
                    } else {
                        throw new IllegalArgumentException(
                                "Avro does not support TIMESTAMP type "
                                        + "with precision: "
                                        + precision
                                        + ", it only supports precision less than 3.");
                    }
                } else {
                    if (precision <= 3) {
                        avroLogicalType = LogicalTypes.localTimestampMillis();
                    } else if (precision <= 6) {
                        avroLogicalType = LogicalTypes.localTimestampMicros();
                    } else {
                        throw new IllegalArgumentException(
                                "Avro does not support LOCAL TIMESTAMP type "
                                        + "with precision: "
                                        + precision
                                        + ", it only supports precision less than 6.");
                    }
                }
                Schema timestamp = avroLogicalType.addToSchema(SchemaBuilder.builder().longType());
                return nullable ? nullableSchema(timestamp) : timestamp;
            case TIMESTAMP_WITH_LOCAL_TIME_ZONE:
                if (legacyTimestampMapping) {
                    throw new UnsupportedOperationException(
                            "Unsupported to derive Schema for type: " + logicalType);
                } else {
                    final LocalZonedTimestampType localZonedTimestampType =
                            (LocalZonedTimestampType) logicalType;
                    precision = localZonedTimestampType.getPrecision();
                    if (precision <= 3) {
                        avroLogicalType = LogicalTypes.timestampMillis();
                    } else if (precision <= 6) {
                        avroLogicalType = LogicalTypes.timestampMicros();
                    } else {
                        throw new IllegalArgumentException(
                                "Avro does not support TIMESTAMP type "
                                        + "with precision: "
                                        + precision
                                        + ", it only supports precision less than 6.");
                    }
                    timestamp = avroLogicalType.addToSchema(SchemaBuilder.builder().longType());
                    return nullable ? nullableSchema(timestamp) : timestamp;
                }
            case DATE:
                // use int to represents Date
                Schema date = LogicalTypes.date().addToSchema(SchemaBuilder.builder().intType());
                return nullable ? nullableSchema(date) : date;
            case TIME_WITHOUT_TIME_ZONE:
                precision = ((TimeType) logicalType).getPrecision();
                if (precision > 3) {
                    throw new IllegalArgumentException(
                            "Avro does not support TIME type with precision: "
                                    + precision
                                    + ", it only supports precision less than 3.");
                }
                // use int to represents Time, we only support millisecond when deserialization
                Schema time =
                        LogicalTypes.timeMillis().addToSchema(SchemaBuilder.builder().intType());
                return nullable ? nullableSchema(time) : time;
            case DECIMAL:
                DecimalType decimalType = (DecimalType) logicalType;
                // store BigDecimal as byte[]
                Schema decimal =
                        LogicalTypes.decimal(decimalType.getPrecision(), decimalType.getScale())
                                .addToSchema(SchemaBuilder.builder().bytesType());
                return nullable ? nullableSchema(decimal) : decimal;
            case ROW:
                RowType rowType = (RowType) logicalType;
                List<String> fieldNames = rowType.getFieldNames();
                // we have to make sure the record name is different in a Schema
                SchemaBuilder.FieldAssembler<Schema> builder =
                        SchemaBuilder.builder().record(rowName).fields();
                for (int i = 0; i < rowType.getFieldCount(); i++) {
                    String fieldName = fieldNames.get(i);
                    LogicalType fieldType = rowType.getTypeAt(i);
                    SchemaBuilder.GenericDefault<Schema> fieldBuilder =
                            builder.name(fieldName)
                                    .type(
                                            convertToSchema(
                                                    fieldType,
                                                    rowName + "_" + fieldName,
                                                    legacyTimestampMapping));

                    if (fieldType.isNullable()) {
                        builder = fieldBuilder.withDefault(null);
                    } else {
                        builder = fieldBuilder.noDefault();
                    }
                }
                Schema record = builder.endRecord();
                return nullable ? nullableSchema(record) : record;
            case MULTISET:
            case MAP:
                Schema map =
                        SchemaBuilder.builder()
                                .map()
                                .values(
                                        convertToSchema(
                                                extractValueTypeToAvroMap(logicalType), rowName));
                return nullable ? nullableSchema(map) : map;
            case ARRAY:
                ArrayType arrayType = (ArrayType) logicalType;
                Schema array =
                        SchemaBuilder.builder()
                                .array()
                                .items(convertToSchema(arrayType.getElementType(), rowName));
                return nullable ? nullableSchema(array) : array;
            case RAW:
            default:
                throw new UnsupportedOperationException(
                        "Unsupported to derive Schema for type: " + logicalType);
        }
    }

    public static LogicalType extractValueTypeToAvroMap(LogicalType type) {
        LogicalType keyType;
        LogicalType valueType;
        if (type instanceof MapType) {
            MapType mapType = (MapType) type;
            keyType = mapType.getKeyType();
            valueType = mapType.getValueType();
        } else {
            MultisetType multisetType = (MultisetType) type;
            keyType = multisetType.getElementType();
            valueType = new IntType();
        }
        if (!keyType.is(LogicalTypeFamily.CHARACTER_STRING)) {
            throw new UnsupportedOperationException(
                    "Avro format doesn't support non-string as key type of map. "
                            + "The key type is: "
                            + keyType.asSummaryString());
        }
        return valueType;
    }

    /** Returns schema with nullable true. */
    private static Schema nullableSchema(Schema schema) {
        return schema.isNullable()
                ? schema
                : Schema.createUnion(SchemaBuilder.builder().nullType(), schema);
    }
}
