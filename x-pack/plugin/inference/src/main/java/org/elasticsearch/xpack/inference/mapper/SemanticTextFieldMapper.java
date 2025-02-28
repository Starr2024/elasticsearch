/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.inference.mapper;

import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.join.BitSetProducer;
import org.apache.lucene.search.join.ScoreMode;
import org.elasticsearch.cluster.metadata.InferenceFieldMetadata;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.core.Tuple;
import org.elasticsearch.features.NodeFeature;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.IndexVersion;
import org.elasticsearch.index.fielddata.FieldDataContext;
import org.elasticsearch.index.fielddata.IndexFieldData;
import org.elasticsearch.index.mapper.BlockLoader;
import org.elasticsearch.index.mapper.BlockSourceReader;
import org.elasticsearch.index.mapper.DocumentParserContext;
import org.elasticsearch.index.mapper.DocumentParsingException;
import org.elasticsearch.index.mapper.FieldMapper;
import org.elasticsearch.index.mapper.InferenceFieldMapper;
import org.elasticsearch.index.mapper.KeywordFieldMapper;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.Mapper;
import org.elasticsearch.index.mapper.MapperBuilderContext;
import org.elasticsearch.index.mapper.MapperMergeContext;
import org.elasticsearch.index.mapper.MappingLookup;
import org.elasticsearch.index.mapper.NestedObjectMapper;
import org.elasticsearch.index.mapper.ObjectMapper;
import org.elasticsearch.index.mapper.SimpleMappedFieldType;
import org.elasticsearch.index.mapper.SourceValueFetcher;
import org.elasticsearch.index.mapper.TextSearchInfo;
import org.elasticsearch.index.mapper.ValueFetcher;
import org.elasticsearch.index.mapper.vectors.DenseVectorFieldMapper;
import org.elasticsearch.index.mapper.vectors.SparseVectorFieldMapper;
import org.elasticsearch.index.query.MatchNoneQueryBuilder;
import org.elasticsearch.index.query.NestedQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.SearchExecutionContext;
import org.elasticsearch.inference.InferenceResults;
import org.elasticsearch.inference.SimilarityMeasure;
import org.elasticsearch.search.vectors.KnnVectorQueryBuilder;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentLocation;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xcontent.XContentParserConfiguration;
import org.elasticsearch.xpack.core.ml.inference.results.MlTextEmbeddingResults;
import org.elasticsearch.xpack.core.ml.inference.results.TextExpansionResults;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static org.elasticsearch.xpack.inference.mapper.SemanticTextField.CHUNKED_EMBEDDINGS_FIELD;
import static org.elasticsearch.xpack.inference.mapper.SemanticTextField.CHUNKED_TEXT_FIELD;
import static org.elasticsearch.xpack.inference.mapper.SemanticTextField.CHUNKS_FIELD;
import static org.elasticsearch.xpack.inference.mapper.SemanticTextField.INFERENCE_FIELD;
import static org.elasticsearch.xpack.inference.mapper.SemanticTextField.INFERENCE_ID_FIELD;
import static org.elasticsearch.xpack.inference.mapper.SemanticTextField.MODEL_SETTINGS_FIELD;
import static org.elasticsearch.xpack.inference.mapper.SemanticTextField.SEARCH_INFERENCE_ID_FIELD;
import static org.elasticsearch.xpack.inference.mapper.SemanticTextField.TEXT_FIELD;
import static org.elasticsearch.xpack.inference.mapper.SemanticTextField.getChunksFieldName;
import static org.elasticsearch.xpack.inference.mapper.SemanticTextField.getEmbeddingsFieldName;
import static org.elasticsearch.xpack.inference.mapper.SemanticTextField.getOriginalTextFieldName;
import static org.elasticsearch.xpack.inference.services.elasticsearch.ElasticsearchInternalService.DEFAULT_ELSER_ID;

/**
 * A {@link FieldMapper} for semantic text fields.
 */
public class SemanticTextFieldMapper extends FieldMapper implements InferenceFieldMapper {
    public static final NodeFeature SEMANTIC_TEXT_SEARCH_INFERENCE_ID = new NodeFeature("semantic_text.search_inference_id");
    public static final NodeFeature SEMANTIC_TEXT_DEFAULT_ELSER_2 = new NodeFeature("semantic_text.default_elser_2");
    public static final NodeFeature SEMANTIC_TEXT_IN_OBJECT_FIELD_FIX = new NodeFeature("semantic_text.in_object_field_fix");

    public static final NodeFeature SEMANTIC_TEXT_SINGLE_FIELD_UPDATE_FIX = new NodeFeature("semantic_text.single_field_update_fix");

    public static final String CONTENT_TYPE = "semantic_text";
    public static final String DEFAULT_ELSER_2_INFERENCE_ID = DEFAULT_ELSER_ID;

    private final IndexSettings indexSettings;

    public static final TypeParser PARSER = new TypeParser(
        (n, c) -> new Builder(n, c.indexVersionCreated(), c::bitSetProducer, c.getIndexSettings()),
        List.of(notInMultiFields(CONTENT_TYPE), notFromDynamicTemplates(CONTENT_TYPE))
    );

    public static class Builder extends FieldMapper.Builder {
        private final IndexVersion indexVersionCreated;
        private final IndexSettings indexSettings;

        private final Parameter<String> inferenceId = Parameter.stringParam(
            INFERENCE_ID_FIELD,
            false,
            mapper -> ((SemanticTextFieldType) mapper.fieldType()).inferenceId,
            DEFAULT_ELSER_2_INFERENCE_ID
        ).addValidator(v -> {
            if (Strings.isEmpty(v)) {
                throw new IllegalArgumentException(
                    "[" + INFERENCE_ID_FIELD + "] on mapper [" + leafName() + "] of type [" + CONTENT_TYPE + "] must not be empty"
                );
            }
        });

        private final Parameter<String> searchInferenceId = Parameter.stringParam(
            SEARCH_INFERENCE_ID_FIELD,
            true,
            mapper -> ((SemanticTextFieldType) mapper.fieldType()).searchInferenceId,
            null
        ).acceptsNull().addValidator(v -> {
            if (v != null && Strings.isEmpty(v)) {
                throw new IllegalArgumentException(
                    "[" + SEARCH_INFERENCE_ID_FIELD + "] on mapper [" + leafName() + "] of type [" + CONTENT_TYPE + "] must not be empty"
                );
            }
        });

        private final Parameter<SemanticTextField.ModelSettings> modelSettings = new Parameter<>(
            MODEL_SETTINGS_FIELD,
            true,
            () -> null,
            (n, c, o) -> SemanticTextField.parseModelSettingsFromMap(o),
            mapper -> ((SemanticTextFieldType) mapper.fieldType()).modelSettings,
            XContentBuilder::field,
            Objects::toString
        ).acceptsNull().setMergeValidator(SemanticTextFieldMapper::canMergeModelSettings);

        private final Parameter<Map<String, String>> meta = Parameter.metaParam();

        private Function<MapperBuilderContext, ObjectMapper> inferenceFieldBuilder;

        public static Builder from(SemanticTextFieldMapper mapper) {
            Builder builder = new Builder(
                mapper.leafName(),
                mapper.fieldType().indexVersionCreated,
                mapper.fieldType().getChunksField().bitsetProducer(),
                mapper.indexSettings
            );
            builder.init(mapper);
            return builder;
        }

        public Builder(
            String name,
            IndexVersion indexVersionCreated,
            Function<Query, BitSetProducer> bitSetProducer,
            IndexSettings indexSettings
        ) {
            super(name);
            this.indexVersionCreated = indexVersionCreated;
            this.indexSettings = indexSettings;
            this.inferenceFieldBuilder = c -> createInferenceField(
                c,
                indexVersionCreated,
                modelSettings.get(),
                bitSetProducer,
                indexSettings
            );
        }

        public Builder setInferenceId(String id) {
            this.inferenceId.setValue(id);
            return this;
        }

        public Builder setSearchInferenceId(String id) {
            this.searchInferenceId.setValue(id);
            return this;
        }

        public Builder setModelSettings(SemanticTextField.ModelSettings value) {
            this.modelSettings.setValue(value);
            return this;
        }

        @Override
        protected Parameter<?>[] getParameters() {
            return new Parameter<?>[] { inferenceId, searchInferenceId, modelSettings, meta };
        }

        @Override
        protected void merge(FieldMapper mergeWith, Conflicts conflicts, MapperMergeContext mapperMergeContext) {
            SemanticTextFieldMapper semanticMergeWith = (SemanticTextFieldMapper) mergeWith;
            semanticMergeWith = copySettings(semanticMergeWith, mapperMergeContext);

            super.merge(semanticMergeWith, conflicts, mapperMergeContext);
            conflicts.check();
            var context = mapperMergeContext.createChildContext(semanticMergeWith.leafName(), ObjectMapper.Dynamic.FALSE);
            var inferenceField = inferenceFieldBuilder.apply(context.getMapperBuilderContext());
            var mergedInferenceField = inferenceField.merge(semanticMergeWith.fieldType().getInferenceField(), context);
            inferenceFieldBuilder = c -> mergedInferenceField;
        }

        @Override
        public SemanticTextFieldMapper build(MapperBuilderContext context) {
            if (copyTo.copyToFields().isEmpty() == false) {
                throw new IllegalArgumentException(CONTENT_TYPE + " field [" + leafName() + "] does not support [copy_to]");
            }
            if (multiFieldsBuilder.hasMultiFields()) {
                throw new IllegalArgumentException(CONTENT_TYPE + " field [" + leafName() + "] does not support multi-fields");
            }
            final String fullName = context.buildFullName(leafName());

            if (context.isInNestedContext()) {
                throw new IllegalArgumentException(CONTENT_TYPE + " field [" + fullName + "] cannot be nested");
            }
            var childContext = context.createChildContext(leafName(), ObjectMapper.Dynamic.FALSE);
            final ObjectMapper inferenceField = inferenceFieldBuilder.apply(childContext);

            return new SemanticTextFieldMapper(
                leafName(),
                new SemanticTextFieldType(
                    fullName,
                    inferenceId.getValue(),
                    searchInferenceId.getValue(),
                    modelSettings.getValue(),
                    inferenceField,
                    indexVersionCreated,
                    meta.getValue()
                ),
                builderParams(this, context),
                indexSettings
            );
        }

        /**
         * As necessary, copy settings from this builder to the passed-in mapper.
         * Used to preserve {@link SemanticTextField.ModelSettings} when updating a semantic text mapping to one where the model settings
         * are not specified.
         *
         * @param mapper The mapper
         * @return A mapper with the copied settings applied
         */
        private SemanticTextFieldMapper copySettings(SemanticTextFieldMapper mapper, MapperMergeContext mapperMergeContext) {
            SemanticTextFieldMapper returnedMapper = mapper;
            if (mapper.fieldType().getModelSettings() == null) {
                Builder builder = from(mapper);
                builder.setModelSettings(modelSettings.getValue());
                returnedMapper = builder.build(mapperMergeContext.getMapperBuilderContext());
            }

            return returnedMapper;
        }
    }

    private SemanticTextFieldMapper(
        String simpleName,
        MappedFieldType mappedFieldType,
        BuilderParams builderParams,
        IndexSettings indexSettings
    ) {
        super(simpleName, mappedFieldType, builderParams);
        this.indexSettings = indexSettings;
    }

    @Override
    public Iterator<Mapper> iterator() {
        List<Mapper> subIterators = new ArrayList<>();
        subIterators.add(fieldType().getInferenceField());
        return subIterators.iterator();
    }

    @Override
    public FieldMapper.Builder getMergeBuilder() {
        return Builder.from(this);
    }

    @Override
    protected void parseCreateField(DocumentParserContext context) throws IOException {
        XContentParser parser = context.parser();
        if (parser.currentToken() == XContentParser.Token.VALUE_NULL) {
            return;
        }

        XContentLocation xContentLocation = parser.getTokenLocation();
        final SemanticTextField field;
        boolean isWithinLeaf = context.path().isWithinLeafObject();
        try {
            context.path().setWithinLeafObject(true);
            field = SemanticTextField.parse(parser, new Tuple<>(fullPath(), context.parser().contentType()));
        } finally {
            context.path().setWithinLeafObject(isWithinLeaf);
        }

        final String fullFieldName = fieldType().name();
        if (field.inference().inferenceId().equals(fieldType().getInferenceId()) == false) {
            throw new DocumentParsingException(
                xContentLocation,
                Strings.format(
                    "The configured %s [%s] for field [%s] doesn't match the %s [%s] reported in the document.",
                    INFERENCE_ID_FIELD,
                    field.inference().inferenceId(),
                    fullFieldName,
                    INFERENCE_ID_FIELD,
                    fieldType().getInferenceId()
                )
            );
        }

        final SemanticTextFieldMapper mapper;
        if (fieldType().getModelSettings() == null) {
            context.path().remove();
            Builder builder = (Builder) new Builder(
                leafName(),
                fieldType().indexVersionCreated,
                fieldType().getChunksField().bitsetProducer(),
                indexSettings
            ).init(this);
            try {
                mapper = builder.setModelSettings(field.inference().modelSettings())
                    .setInferenceId(field.inference().inferenceId())
                    .build(context.createDynamicMapperBuilderContext());
                context.addDynamicMapper(mapper);
            } finally {
                context.path().add(leafName());
            }
        } else {
            Conflicts conflicts = new Conflicts(fullFieldName);
            canMergeModelSettings(fieldType().getModelSettings(), field.inference().modelSettings(), conflicts);
            try {
                conflicts.check();
            } catch (Exception exc) {
                throw new DocumentParsingException(
                    xContentLocation,
                    "Incompatible model settings for field ["
                        + fullPath()
                        + "]. Check that the "
                        + INFERENCE_ID_FIELD
                        + " is not using different model settings",
                    exc
                );
            }
            mapper = this;
        }

        var chunksField = mapper.fieldType().getChunksField();
        var embeddingsField = mapper.fieldType().getEmbeddingsField();
        for (var chunk : field.inference().chunks()) {
            try (
                XContentParser subParser = XContentHelper.createParserNotCompressed(
                    XContentParserConfiguration.EMPTY,
                    chunk.rawEmbeddings(),
                    context.parser().contentType()
                )
            ) {
                DocumentParserContext subContext = context.createNestedContext(chunksField).switchParser(subParser);
                subParser.nextToken();
                embeddingsField.parse(subContext);
            }
        }
    }

    @Override
    protected String contentType() {
        return CONTENT_TYPE;
    }

    @Override
    public SemanticTextFieldType fieldType() {
        return (SemanticTextFieldType) super.fieldType();
    }

    @Override
    public InferenceFieldMetadata getMetadata(Set<String> sourcePaths) {
        String[] copyFields = sourcePaths.toArray(String[]::new);
        // ensure consistent order
        Arrays.sort(copyFields);
        return new InferenceFieldMetadata(fullPath(), fieldType().getInferenceId(), fieldType().getSearchInferenceId(), copyFields);
    }

    @Override
    public Object getOriginalValue(Map<String, Object> sourceAsMap) {
        Object fieldValue = sourceAsMap.get(fullPath());
        if (fieldValue == null) {
            return null;
        } else if (fieldValue instanceof Map<?, ?> == false) {
            // Don't try to further validate the non-map value, that will be handled when the source is fully parsed
            return fieldValue;
        }

        Map<String, Object> fieldValueMap = XContentMapValues.nodeMapValue(fieldValue, "Field [" + fullPath() + "]");
        return XContentMapValues.extractValue(TEXT_FIELD, fieldValueMap);
    }

    @Override
    protected void doValidate(MappingLookup mappers) {
        int parentPathIndex = fullPath().lastIndexOf(leafName());
        if (parentPathIndex > 0) {
            // Check that the parent object field allows subobjects.
            // Subtract one from the parent path index to omit the trailing dot delimiter.
            ObjectMapper parentMapper = mappers.objectMappers().get(fullPath().substring(0, parentPathIndex - 1));
            if (parentMapper == null) {
                throw new IllegalStateException(CONTENT_TYPE + " field [" + fullPath() + "] does not have a parent object mapper");
            }

            if (parentMapper.subobjects() == ObjectMapper.Subobjects.DISABLED) {
                throw new IllegalArgumentException(
                    CONTENT_TYPE + " field [" + fullPath() + "] cannot be in an object field with subobjects disabled"
                );
            }
        }
    }

    public static class SemanticTextFieldType extends SimpleMappedFieldType {
        private final String inferenceId;
        private final String searchInferenceId;
        private final SemanticTextField.ModelSettings modelSettings;
        private final ObjectMapper inferenceField;
        private final IndexVersion indexVersionCreated;

        public SemanticTextFieldType(
            String name,
            String inferenceId,
            String searchInferenceId,
            SemanticTextField.ModelSettings modelSettings,
            ObjectMapper inferenceField,
            IndexVersion indexVersionCreated,
            Map<String, String> meta
        ) {
            super(name, true, false, false, TextSearchInfo.NONE, meta);
            this.inferenceId = inferenceId;
            this.searchInferenceId = searchInferenceId;
            this.modelSettings = modelSettings;
            this.inferenceField = inferenceField;
            this.indexVersionCreated = indexVersionCreated;
        }

        @Override
        public String typeName() {
            return CONTENT_TYPE;
        }

        public String getInferenceId() {
            return inferenceId;
        }

        public String getSearchInferenceId() {
            return searchInferenceId == null ? inferenceId : searchInferenceId;
        }

        public SemanticTextField.ModelSettings getModelSettings() {
            return modelSettings;
        }

        public ObjectMapper getInferenceField() {
            return inferenceField;
        }

        public NestedObjectMapper getChunksField() {
            return (NestedObjectMapper) inferenceField.getMapper(CHUNKS_FIELD);
        }

        public FieldMapper getEmbeddingsField() {
            return (FieldMapper) getChunksField().getMapper(CHUNKED_EMBEDDINGS_FIELD);
        }

        @Override
        public Query termQuery(Object value, SearchExecutionContext context) {
            throw new IllegalArgumentException(CONTENT_TYPE + " fields do not support term query");
        }

        @Override
        public Query existsQuery(SearchExecutionContext context) {
            if (getEmbeddingsField() == null) {
                return new MatchNoDocsQuery();
            }

            return NestedQueryBuilder.toQuery(
                (c -> getEmbeddingsField().fieldType().existsQuery(c)),
                getChunksFieldName(name()),
                ScoreMode.None,
                false,
                context
            );
        }

        @Override
        public ValueFetcher valueFetcher(SearchExecutionContext context, String format) {
            // Redirect the fetcher to load the original values of the field
            return SourceValueFetcher.toString(getOriginalTextFieldName(name()), context, format);
        }

        @Override
        public IndexFieldData.Builder fielddataBuilder(FieldDataContext fieldDataContext) {
            throw new IllegalArgumentException("[semantic_text] fields do not support sorting, scripting or aggregating");
        }

        @Override
        public boolean fieldHasValue(FieldInfos fieldInfos) {
            return fieldInfos.fieldInfo(getEmbeddingsFieldName(name())) != null;
        }

        public QueryBuilder semanticQuery(InferenceResults inferenceResults, float boost, String queryName) {
            String nestedFieldPath = getChunksFieldName(name());
            String inferenceResultsFieldName = getEmbeddingsFieldName(name());
            QueryBuilder childQueryBuilder;

            if (modelSettings == null) {
                // No inference results have been indexed yet
                childQueryBuilder = new MatchNoneQueryBuilder();
            } else {
                childQueryBuilder = switch (modelSettings.taskType()) {
                    case SPARSE_EMBEDDING -> {
                        if (inferenceResults instanceof TextExpansionResults == false) {
                            throw new IllegalArgumentException(
                                generateQueryInferenceResultsTypeMismatchMessage(inferenceResults, TextExpansionResults.NAME)
                            );
                        }

                        // TODO: Use WeightedTokensQueryBuilder
                        TextExpansionResults textExpansionResults = (TextExpansionResults) inferenceResults;
                        var boolQuery = QueryBuilders.boolQuery();
                        for (var weightedToken : textExpansionResults.getWeightedTokens()) {
                            boolQuery.should(
                                QueryBuilders.termQuery(inferenceResultsFieldName, weightedToken.token()).boost(weightedToken.weight())
                            );
                        }
                        boolQuery.minimumShouldMatch(1);

                        yield boolQuery;
                    }
                    case TEXT_EMBEDDING -> {
                        if (inferenceResults instanceof MlTextEmbeddingResults == false) {
                            throw new IllegalArgumentException(
                                generateQueryInferenceResultsTypeMismatchMessage(inferenceResults, MlTextEmbeddingResults.NAME)
                            );
                        }

                        MlTextEmbeddingResults textEmbeddingResults = (MlTextEmbeddingResults) inferenceResults;
                        float[] inference = textEmbeddingResults.getInferenceAsFloat();
                        if (inference.length != modelSettings.dimensions()) {
                            throw new IllegalArgumentException(
                                generateDimensionCountMismatchMessage(inference.length, modelSettings.dimensions())
                            );
                        }

                        yield new KnnVectorQueryBuilder(inferenceResultsFieldName, inference, null, null, null);
                    }
                    default -> throw new IllegalStateException(
                        "Field ["
                            + name()
                            + "] is configured to use an inference endpoint with an unsupported task type ["
                            + modelSettings.taskType()
                            + "]"
                    );
                };
            }

            return new NestedQueryBuilder(nestedFieldPath, childQueryBuilder, ScoreMode.Max).boost(boost).queryName(queryName);
        }

        private String generateQueryInferenceResultsTypeMismatchMessage(InferenceResults inferenceResults, String expectedResultsType) {
            StringBuilder sb = new StringBuilder(
                "Field ["
                    + name()
                    + "] expected query inference results to be of type ["
                    + expectedResultsType
                    + "],"
                    + " got ["
                    + inferenceResults.getWriteableName()
                    + "]."
            );

            return generateInvalidQueryInferenceResultsMessage(sb);
        }

        private String generateDimensionCountMismatchMessage(int inferenceDimCount, int expectedDimCount) {
            StringBuilder sb = new StringBuilder(
                "Field ["
                    + name()
                    + "] expected query inference results with "
                    + expectedDimCount
                    + " dimensions, got "
                    + inferenceDimCount
                    + " dimensions."
            );

            return generateInvalidQueryInferenceResultsMessage(sb);
        }

        private String generateInvalidQueryInferenceResultsMessage(StringBuilder baseMessageBuilder) {
            if (searchInferenceId != null && searchInferenceId.equals(inferenceId) == false) {
                baseMessageBuilder.append(
                    " Is the search inference endpoint ["
                        + searchInferenceId
                        + "] compatible with the inference endpoint ["
                        + inferenceId
                        + "]?"
                );
            } else {
                baseMessageBuilder.append(" Has the configuration for inference endpoint [" + inferenceId + "] changed?");
            }

            return baseMessageBuilder.toString();
        }

        @Override
        public BlockLoader blockLoader(MappedFieldType.BlockLoaderContext blContext) {
            SourceValueFetcher fetcher = SourceValueFetcher.toString(blContext.sourcePaths(name().concat(".text")));
            return new BlockSourceReader.BytesRefsBlockLoader(fetcher, BlockSourceReader.lookupMatchingAll());
        }
    }

    /**
     * <p>
     * Insert or replace the path's value in the map with the provided new value. The map will be modified in-place.
     * If the complete path does not exist in the map, it will be added to the deepest (sub-)map possible.
     * </p>
     * <p>
     * For example, given the map:
     * </p>
     * <pre>
     * {
     *   "path1": {
     *     "path2": {
     *       "key1": "value1"
     *     }
     *   }
     * }
     * </pre>
     * <p>
     * And the caller wanted to insert {@code "path1.path2.path3.key2": "value2"}, the method would emit the modified map:
     * </p>
     * <pre>
     * {
     *   "path1": {
     *     "path2": {
     *       "key1": "value1",
     *       "path3.key2": "value2"
     *     }
     *   }
     * }
     * </pre>
     *
     * @param path the value's path in the map.
     * @param map the map to search and modify in-place.
     * @param newValue the new value to assign to the path.
     *
     * @throws IllegalArgumentException If either the path cannot be fully traversed or there is ambiguity about where to insert the new
     *                                  value.
     */
    public static void insertValue(String path, Map<?, ?> map, Object newValue) {
        String[] pathElements = path.split("\\.");
        if (pathElements.length == 0) {
            return;
        }

        List<SuffixMap> suffixMaps = extractSuffixMaps(pathElements, 0, map);
        if (suffixMaps.isEmpty()) {
            // This should never happen. Throw in case it does for some reason.
            throw new IllegalStateException("extractSuffixMaps returned an empty suffix map list");
        } else if (suffixMaps.size() == 1) {
            SuffixMap suffixMap = suffixMaps.getFirst();
            suffixMap.map().put(suffixMap.suffix(), newValue);
        } else {
            throw new IllegalArgumentException(
                "Path [" + path + "] could be inserted in " + suffixMaps.size() + " distinct ways, it is ambiguous which one to use"
            );
        }
    }

    private record SuffixMap(String suffix, Map<String, Object> map) {}

    private static List<SuffixMap> extractSuffixMaps(String[] pathElements, int index, Object currentValue) {
        if (currentValue instanceof List<?> valueList) {
            List<SuffixMap> suffixMaps = new ArrayList<>(valueList.size());
            for (Object o : valueList) {
                suffixMaps.addAll(extractSuffixMaps(pathElements, index, o));
            }

            return suffixMaps;
        } else if (currentValue instanceof Map<?, ?>) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) currentValue;
            List<SuffixMap> suffixMaps = new ArrayList<>(map.size());

            String key = pathElements[index];
            while (index < pathElements.length) {
                if (map.containsKey(key)) {
                    if (index + 1 == pathElements.length) {
                        // We found the complete path
                        suffixMaps.add(new SuffixMap(key, map));
                    } else {
                        // We've matched that path partially, keep traversing to try to match it fully
                        suffixMaps.addAll(extractSuffixMaps(pathElements, index + 1, map.get(key)));
                    }
                }

                if (++index < pathElements.length) {
                    key += "." + pathElements[index];
                }
            }

            if (suffixMaps.isEmpty()) {
                // We checked for all remaining elements in the path, and they do not exist. This means we found a leaf map that we should
                // add the value to.
                suffixMaps.add(new SuffixMap(key, map));
            }

            return suffixMaps;
        } else {
            throw new IllegalArgumentException(
                "Path ["
                    + String.join(".", Arrays.copyOfRange(pathElements, 0, index))
                    + "] has value ["
                    + currentValue
                    + "] of type ["
                    + currentValue.getClass().getSimpleName()
                    + "], which cannot be traversed into further"
            );
        }
    }

    private static ObjectMapper createInferenceField(
        MapperBuilderContext context,
        IndexVersion indexVersionCreated,
        @Nullable SemanticTextField.ModelSettings modelSettings,
        Function<Query, BitSetProducer> bitSetProducer,
        IndexSettings indexSettings
    ) {
        return new ObjectMapper.Builder(INFERENCE_FIELD, Optional.of(ObjectMapper.Subobjects.ENABLED)).dynamic(ObjectMapper.Dynamic.FALSE)
            .add(createChunksField(indexVersionCreated, modelSettings, bitSetProducer, indexSettings))
            .build(context);
    }

    private static NestedObjectMapper.Builder createChunksField(
        IndexVersion indexVersionCreated,
        @Nullable SemanticTextField.ModelSettings modelSettings,
        Function<Query, BitSetProducer> bitSetProducer,
        IndexSettings indexSettings
    ) {
        NestedObjectMapper.Builder chunksField = new NestedObjectMapper.Builder(
            CHUNKS_FIELD,
            indexVersionCreated,
            bitSetProducer,
            indexSettings
        );
        chunksField.dynamic(ObjectMapper.Dynamic.FALSE);
        KeywordFieldMapper.Builder chunkTextField = new KeywordFieldMapper.Builder(CHUNKED_TEXT_FIELD, indexVersionCreated).indexed(false)
            .docValues(false);
        if (modelSettings != null) {
            chunksField.add(createEmbeddingsField(indexVersionCreated, modelSettings));
        }
        chunksField.add(chunkTextField);
        return chunksField;
    }

    private static Mapper.Builder createEmbeddingsField(IndexVersion indexVersionCreated, SemanticTextField.ModelSettings modelSettings) {
        return switch (modelSettings.taskType()) {
            case SPARSE_EMBEDDING -> new SparseVectorFieldMapper.Builder(CHUNKED_EMBEDDINGS_FIELD);
            case TEXT_EMBEDDING -> {
                DenseVectorFieldMapper.Builder denseVectorMapperBuilder = new DenseVectorFieldMapper.Builder(
                    CHUNKED_EMBEDDINGS_FIELD,
                    indexVersionCreated
                );

                SimilarityMeasure similarity = modelSettings.similarity();
                if (similarity != null) {
                    switch (similarity) {
                        case COSINE -> denseVectorMapperBuilder.similarity(DenseVectorFieldMapper.VectorSimilarity.COSINE);
                        case DOT_PRODUCT -> denseVectorMapperBuilder.similarity(DenseVectorFieldMapper.VectorSimilarity.DOT_PRODUCT);
                        case L2_NORM -> denseVectorMapperBuilder.similarity(DenseVectorFieldMapper.VectorSimilarity.L2_NORM);
                        default -> throw new IllegalArgumentException(
                            "Unknown similarity measure in model_settings [" + similarity.name() + "]"
                        );
                    }
                }
                denseVectorMapperBuilder.dimensions(modelSettings.dimensions());
                denseVectorMapperBuilder.elementType(modelSettings.elementType());

                yield denseVectorMapperBuilder;
            }
            default -> throw new IllegalArgumentException("Invalid task_type in model_settings [" + modelSettings.taskType().name() + "]");
        };
    }

    private static boolean canMergeModelSettings(
        SemanticTextField.ModelSettings previous,
        SemanticTextField.ModelSettings current,
        Conflicts conflicts
    ) {
        if (Objects.equals(previous, current)) {
            return true;
        }
        if (previous == null) {
            return true;
        }
        conflicts.addConflict("model_settings", "");
        return false;
    }
}
