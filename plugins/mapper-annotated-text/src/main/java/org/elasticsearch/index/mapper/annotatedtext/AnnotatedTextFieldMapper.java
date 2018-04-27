/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.mapper.annotatedtext;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.Analyzer.TokenStreamComponents;
import org.apache.lucene.analysis.AnalyzerWrapper;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute;
import org.apache.lucene.analysis.tokenattributes.TermToBytesRefAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.NormsFieldExistsQuery;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.index.analysis.AnalyzerScope;
import org.elasticsearch.index.analysis.NamedAnalyzer;
import org.elasticsearch.index.mapper.FieldMapper;
import org.elasticsearch.index.mapper.FieldNamesFieldMapper;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.Mapper;
import org.elasticsearch.index.mapper.MapperParsingException;
import org.elasticsearch.index.mapper.ParseContext;
import org.elasticsearch.index.mapper.StringFieldType;
import org.elasticsearch.index.mapper.TextFieldMapper;
import org.elasticsearch.index.mapper.annotatedtext.AnnotatedTextFieldMapper.AnnotatedText.AnnotationToken;
import org.elasticsearch.index.query.QueryShardContext;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.elasticsearch.index.mapper.TypeParsers.parseTextField;

/** A {@link FieldMapper} for full-text fields with annotation markup e.g.
 * 
 *    "New mayor is [John Smith](type=person&amp;value=John%20Smith) "
 * 
 * A special Analyzer wraps the default choice of analyzer in order
 * to strip the text field of annotation markup and inject the related
 * entity annotation tokens as supplementary tokens at the relevant points
 * in the token stream.
 * This code is largely a copy of TextFieldMapper which is less than ideal - 
 * my attempts to subclass TextFieldMapper faild but we can revisit this.
 **/
public class AnnotatedTextFieldMapper extends FieldMapper {

    public static final String CONTENT_TYPE = "annotated_text";
    private static final int POSITION_INCREMENT_GAP_USE_ANALYZER = -1;

    public static class Defaults {
        public static final int INDEX_PREFIX_MIN_CHARS = 2;
        public static final int INDEX_PREFIX_MAX_CHARS = 5;

        public static final MappedFieldType FIELD_TYPE = new AnnotatedTextFieldType();

        static {
            FIELD_TYPE.freeze();
        }

    }

    public static class Builder extends FieldMapper.Builder<Builder, AnnotatedTextFieldMapper> {

        private int positionIncrementGap = POSITION_INCREMENT_GAP_USE_ANALYZER;
        
        public Builder(String name) {
            super(name, Defaults.FIELD_TYPE, Defaults.FIELD_TYPE);
            builder = this;
        }

        @Override
        public AnnotatedTextFieldType fieldType() {
            return (AnnotatedTextFieldType) super.fieldType();
        }

        public Builder positionIncrementGap(int positionIncrementGap) {
            if (positionIncrementGap < 0) {
                throw new MapperParsingException("[positions_increment_gap] must be positive, got " + positionIncrementGap);
            }
            this.positionIncrementGap = positionIncrementGap;
            return this;
        }

//        public Builder indexPhrases(boolean indexPhrases) {
//            fieldType().setIndexPhrases(indexPhrases);
//            return builder;
//        }
        
        @Override
        public Builder docValues(boolean docValues) {
            if (docValues) {
                throw new IllegalArgumentException("[text] fields do not support doc values");
            }
            return super.docValues(docValues);
        }

        public Builder eagerGlobalOrdinals(boolean eagerGlobalOrdinals) {
            fieldType().setEagerGlobalOrdinals(eagerGlobalOrdinals);
            return builder;
        }

        @Override
        public AnnotatedTextFieldMapper build(BuilderContext context) {
            if (positionIncrementGap != POSITION_INCREMENT_GAP_USE_ANALYZER) {
                if (fieldType.indexOptions().compareTo(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS) < 0) {
                    throw new IllegalArgumentException("Cannot set position_increment_gap on field ["
                        + name + "] without positions enabled");
                }
                fieldType.setIndexAnalyzer(new NamedAnalyzer(fieldType.indexAnalyzer(), positionIncrementGap));
                fieldType.setSearchAnalyzer(new NamedAnalyzer(fieldType.searchAnalyzer(), positionIncrementGap));
                fieldType.setSearchQuoteAnalyzer(new NamedAnalyzer(fieldType.searchQuoteAnalyzer(), positionIncrementGap));
            } else {
                //Using the analyzer's default BUT need to do the same thing AnalysisRegistry.processAnalyzerFactory 
                // does to splice in new default of posIncGap=100 by wrapping the analyzer                
                if (fieldType.indexOptions().compareTo(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS) >= 0) {
                    int overrideInc = TextFieldMapper.Defaults.POSITION_INCREMENT_GAP;
                    fieldType.setIndexAnalyzer(new NamedAnalyzer(fieldType.indexAnalyzer(), overrideInc));
                    fieldType.setSearchAnalyzer(new NamedAnalyzer(fieldType.searchAnalyzer(), overrideInc));
                    fieldType.setSearchQuoteAnalyzer(new NamedAnalyzer(fieldType.searchQuoteAnalyzer(),overrideInc));
                }
            }
            setupFieldType(context);
            return new AnnotatedTextFieldMapper(
                    name, fieldType(), defaultFieldType, positionIncrementGap,
                    context.indexSettings(), multiFieldsBuilder.build(this, context), copyTo);
        }
    }

    public static class TypeParser implements Mapper.TypeParser {
        @Override
        public Mapper.Builder<AnnotatedTextFieldMapper.Builder, AnnotatedTextFieldMapper> parse(
                String fieldName, Map<String, Object> node, ParserContext parserContext) throws MapperParsingException {
            AnnotatedTextFieldMapper.Builder builder = new AnnotatedTextFieldMapper.Builder(fieldName);
            
            builder.fieldType().setIndexAnalyzer(parserContext.getIndexAnalyzers().getDefaultIndexAnalyzer());
            builder.fieldType().setSearchAnalyzer(parserContext.getIndexAnalyzers().getDefaultSearchAnalyzer());
            builder.fieldType().setSearchQuoteAnalyzer(parserContext.getIndexAnalyzers().getDefaultSearchQuoteAnalyzer());
            parseTextField(builder, fieldName, node, parserContext);
            for (Iterator<Map.Entry<String, Object>> iterator = node.entrySet().iterator(); iterator.hasNext();) {
                Map.Entry<String, Object> entry = iterator.next();
                String propName = entry.getKey();
                Object propNode = entry.getValue();
                if (propName.equals("position_increment_gap")) {
                    int newPositionIncrementGap = XContentMapValues.nodeIntegerValue(propNode, -1);
                    builder.positionIncrementGap(newPositionIncrementGap);
                    iterator.remove();
                } else if (propName.equals("eager_global_ordinals")) {
                    builder.eagerGlobalOrdinals(XContentMapValues.nodeBooleanValue(propNode, "eager_global_ordinals"));
                    iterator.remove();
                }
            }
            return builder;
        }
    }

    
    /**
     * Parses markdown-like syntax into plain text and AnnotationTokens with offsets for
     * annotations found in texts
     */
    public static final class AnnotatedText {
        public final String textPlusMarkup;
        public final String textMinusMarkup;
        List<AnnotationToken> annotations =new ArrayList<>();
        
        // Format is markdown-like syntax for URLs eg:
        //   "New mayor is [John Smith](type=person&value=John%20Smith) "
        Pattern markdownPattern = Pattern.compile("\\[([^\\]\\[]*)\\]\\(([^\\)\\(]*)\\)");  

        public AnnotatedText(String textPlusMarkup) {
            super();
            this.textPlusMarkup = textPlusMarkup;
            Matcher m = markdownPattern.matcher(textPlusMarkup);                
            int lastPos = 0;
            StringBuilder sb = new StringBuilder();
            while(m.find()){
                if(m.start() > lastPos){
                    sb.append(textPlusMarkup.substring(lastPos, m.start()));
                }
                
                int startOffset = sb.length();
                int endOffset = sb.length() + m.group(1).length();
                sb.append(m.group(1));
                lastPos = m.end();
                
                String[] pairs = m.group(2).split("&");
                String type = null;
                String value = null;
                for (String pair : pairs) {
                    String[] kv = pair.split("=");
                    try {
                        if(kv.length == 2){                            
                            type = URLDecoder.decode(kv[0], "UTF-8");
                            value = URLDecoder.decode(kv[1], "UTF-8");
                        }
                        if(kv.length == 1) {
                            //Check "=" sign wasn't in the pair string
                            if(kv[0].length() == pair.length()) {
                                //untyped value
                                value = URLDecoder.decode(kv[0], "UTF-8");
                            }
                        }
                        if (value!=null && value.length() > 0) {
                            annotations.add(new AnnotationToken(startOffset, endOffset, type, value));
                        }
                    } catch (UnsupportedEncodingException uee){
                        throw new ElasticsearchParseException("Unsupported encoding parsing annotated text", uee);
                    }                        
                }                      
            }   
            if(lastPos < textPlusMarkup.length()){
                sb.append(textPlusMarkup.substring(lastPos));
            }
            
            
            textMinusMarkup = sb.toString();
            
        }
        
        public static final class AnnotationToken {
            public final int offset;
            public final int endOffset;
            
            public final String type;
            public final String value;
            public AnnotationToken(int offset, int endOffset, String type, String value) {
                super();
                this.offset = offset;
                this.endOffset = endOffset;
                this.type = type;
                this.value = value;
            }
            @Override
            public String toString() {
               return value +" ("+offset+" - "+endOffset+")";
            }
            
            public boolean overlaps(int proposedBreakPos) {
                return proposedBreakPos >= offset && proposedBreakPos <= endOffset;
            }   
            
            public boolean intersects(int start, int end) {
                return (start <= offset && end >= offset) || (start <= endOffset && end >= endOffset)
                        || (start >= offset && end <= endOffset);
            }
            
            @Override
            public int hashCode() {
                final int prime = 31;
                int result = 1;
                result = prime * result + endOffset;
                result = prime * result + offset;
                result = prime * result + Objects.hashCode(type);
                result = prime * result + Objects.hashCode(value);
                return result;
            }
            
            @Override
            public boolean equals(Object obj) {
                if (this == obj)
                    return true;
                if (obj == null)
                    return false;
                if (getClass() != obj.getClass())
                    return false;
                AnnotationToken other = (AnnotationToken) obj;
                return Objects.equals(endOffset, other.endOffset) && Objects.equals(offset, other.offset)
                        && Objects.equals(type, other.type) && Objects.equals(value, other.value);
            }
            
        }
        
        @Override
        public String toString() {
           StringBuilder sb = new StringBuilder();
           sb.append(textMinusMarkup);
           sb.append("\n");
           annotations.forEach(a -> {sb.append(a); sb.append("\n");});
           return sb.toString();
        }

        public int numAnnotations() {
            return annotations.size();
        }

        public AnnotationToken getAnnotation(int index) {
            return annotations.get(index);
        }   
    }
    
    // A utility class for use with highlighters where the content being highlighted 
    // needs plain text format for highlighting but marked-up format for token discovery.
    // The class takes markedup format field values and returns plain text versions.
    // When asked to tokenize plain-text versions by the highlighter it tokenizes the
    // original markup form in order to inject annotations.
    public static final class AnnotatedHighlighterAnalyzer extends AnalyzerWrapper {
        private Analyzer delegate;
        private AnnotatedText[] annotations;
        public AnnotatedHighlighterAnalyzer(String [] markedUpFieldValues, Analyzer delegate){
            super(delegate.getReuseStrategy());
            this.delegate = delegate;
            this.annotations = new AnnotatedText[markedUpFieldValues.length];
            for (int i = 0; i < markedUpFieldValues.length; i++) {
                annotations[i] = new AnnotatedText(markedUpFieldValues[i]);
            }
        }
        
        public String []  getPlainTextValuesForHighlighter(){
            String [] result = new String[annotations.length];
            for (int i = 0; i < annotations.length; i++) {
                result[i] = annotations[i].textMinusMarkup;
            }
            return result;
        }
        
        public AnnotationToken[] getIntersectingAnnotations(int start, int end) {
            List<AnnotationToken> intersectingAnnotations = new ArrayList<>();
            int fieldValueOffset =0;
            for (AnnotatedText fieldValueAnnotations : this.annotations) {
                //This is called from a highlighter where all of the field values are concatenated
                // so each annotation offset will need to be adjusted so that it takes into account 
                // the previous values AND the MULTIVAL delimiter
                for (AnnotationToken token : fieldValueAnnotations.annotations) {
                    if(token.intersects(start - fieldValueOffset , end - fieldValueOffset)) {
                        intersectingAnnotations.add(new AnnotationToken(token.offset + fieldValueOffset, 
                                token.endOffset + fieldValueOffset, token.type, token.value));
                    }
                } 
                //add 1 for the fieldvalue separator character
                fieldValueOffset +=fieldValueAnnotations.textMinusMarkup.length() +1;
            }
            return intersectingAnnotations.toArray(new AnnotationToken[intersectingAnnotations.size()]);
        }        
        
        @Override
        public Analyzer getWrappedAnalyzer(String fieldName) {
          return delegate;
        }   
        
        @Override
        protected TokenStreamComponents wrapComponents(String fieldName, TokenStreamComponents components) {
            if(components instanceof AnnotatedHighlighterTokenStreamComponents){
                // already wrapped.
                return components;
            }
            AnnotationsInjector injector = new AnnotationsInjector(components.getTokenStream());
            return new AnnotatedHighlighterTokenStreamComponents(components.getTokenizer(), injector, this.annotations);
        }        
    }
    private static final class AnnotatedHighlighterTokenStreamComponents extends TokenStreamComponents{

        private AnnotationsInjector annotationsInjector;
        private AnnotatedText[] annotations;
        int readerNum = 0;

        AnnotatedHighlighterTokenStreamComponents(Tokenizer source, AnnotationsInjector annotationsFilter,
                AnnotatedText[] annotations) {
            super(source, annotationsFilter);
            this.annotationsInjector = annotationsFilter;
            this.annotations = annotations;            
        }

        @Override
        protected void setReader(Reader reader) {
            String plainText = readToString(reader);
            AnnotatedText at = this.annotations[readerNum++];
            assert at.textMinusMarkup.equals(plainText);
            // This code is reliant on the behaviour of highlighter logic - it 
            // takes plain text multi-value fields and then calls the same analyzer 
            // for each field value in turn. This class has cached the annotations
            // associated with each plain-text value and are arranged in the same order
            annotationsInjector.setAnnotations(at);
            super.setReader(new StringReader(at.textMinusMarkup));  
        }
               
    }    
    
    
    public static final class AnnotationAnalyzerWrapper extends AnalyzerWrapper {
        

        private final Analyzer delegate;

        public AnnotationAnalyzerWrapper (Analyzer delegate) {
          super(delegate.getReuseStrategy());
          this.delegate = delegate;
        }

        /**
         * Wraps {@link StandardAnalyzer}. 
         */
        public AnnotationAnalyzerWrapper() {
          this(new StandardAnalyzer());
        }
        

        @Override
        public Analyzer getWrappedAnalyzer(String fieldName) {
          return delegate;
        }     

        @Override
        protected TokenStreamComponents wrapComponents(String fieldName, TokenStreamComponents components) {
            if(components instanceof AnnotatedTokenStreamComponents){
                // already wrapped.
                return components;
            }
            AnnotationsInjector injector = new AnnotationsInjector(components.getTokenStream());
            return new AnnotatedTokenStreamComponents(components.getTokenizer(), injector);
        }

        @Override
        protected Reader wrapReader(String fieldName, Reader reader) {
            return new AnnotatedReader(reader);
        }                
        
      }
    
    // Class used to propagate AnnotatedText information to an AnnotationsFilter in the TokenStreamComponents 
    private static class AnnotatedReader extends Reader{
        Reader delegate;
        private final AnnotatedText parsedText;
        static final ThreadLocal<AnnotationsInjector> threadLocalAnnotationInjector =new ThreadLocal<>();
        private boolean isFirstRead = true;
        
        static void registerAnnotationInjector(AnnotationsInjector annotationsInjector) {
            threadLocalAnnotationInjector.set(annotationsInjector);
        }
        
        AnnotatedReader (Reader reader) {
            parsedText = new AnnotatedText(readToString(reader));
            delegate = new StringReader(parsedText.textMinusMarkup);            
        }

        @Override
        public int read(char[] cbuf, int off, int len) throws IOException {
            if (isFirstRead) {
                isFirstRead = false;
                // If this analysis pipeline has an active annotations filter,
                // prime it with the annotations
                AnnotationsInjector annotationsInjector = threadLocalAnnotationInjector.get();
                if (annotationsInjector != null) {
                    annotationsInjector.setAnnotations(parsedText);
                    threadLocalAnnotationInjector.set(null);
                }
            }
            return delegate.read(cbuf, off, len);
        }

        @Override
        public void close() throws IOException {
            threadLocalAnnotationInjector.set(null);
            delegate.close();
        }   
    }
    
    //When other Analyzers "wrap" this one they throw away this class and replace with another TokenStreamComponents
    //instance and additional filters. In this scenario the token stream reverts to plain-text tokens (minus annotations)
    // because the crucial AnnotatedReader.registerAnnotationFilter is not invoked as the annotationsInjector is not then
    // hooked up with a source of annotations.
    private static final class AnnotatedTokenStreamComponents extends TokenStreamComponents{
        private AnnotationsInjector annotationsInjector;

        AnnotatedTokenStreamComponents(Tokenizer source, AnnotationsInjector annotationsInjector) {
            super(source, annotationsInjector);
            this.annotationsInjector = annotationsInjector;
        }

        @Override
        protected void setReader(Reader reader) {     
            // Necessary hack to get Lucene TokenFilter and any annotations parsed from the Reader to meet up using a ThreadLocal
            AnnotatedReader.registerAnnotationInjector(annotationsInjector);                        
            super.setReader(reader);            
        }
    }
    
    static String readToString(Reader reader) {       
        char[] arr = new char[8 * 1024];
        StringBuilder buffer = new StringBuilder();
        int numCharsRead;
        try {
            while ((numCharsRead = reader.read(arr, 0, arr.length)) != -1) {
                buffer.append(arr, 0, numCharsRead);
            }
            reader.close();
            return buffer.toString();            
        } catch (IOException e) {
            throw new ElasticsearchException("IO Error reading field content", e);
        }
    }         

    
    public static final class AnnotationsInjector extends TokenFilter {
        
        private AnnotatedText annotatedText;
        AnnotatedText.AnnotationToken nextAnnotationForInjection = null;
        private int currentAnnotationIndex = 0;
        List<State> pendingStates = new ArrayList<>();
        int pendingStatePos = 0;
        boolean inputExhausted = false;

        private final OffsetAttribute textOffsetAtt = addAttribute(OffsetAttribute.class);
        private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
        private final PositionIncrementAttribute posAtt = addAttribute(PositionIncrementAttribute.class);
        private final PositionLengthAttribute posLenAtt = addAttribute(PositionLengthAttribute.class);
        private final TypeAttribute typeAtt = addAttribute(TypeAttribute.class);

        public AnnotationsInjector(TokenStream in) {
          super(in);
        }

        public void setAnnotations(AnnotatedText annotatedText) {
          this.annotatedText = annotatedText;
          currentAnnotationIndex = 0;
          if(annotatedText!=null && annotatedText.numAnnotations()>0){
              nextAnnotationForInjection = annotatedText.getAnnotation(0);
          } else {
              nextAnnotationForInjection = null;
          }
        }
        
        

        @Override
        public void reset() throws IOException {
            pendingStates.clear();
            pendingStatePos = 0;
            inputExhausted = false;
            super.reset();
        }
        
        // Abstracts if we are pulling from some pre-cached buffer of
        // text tokens or directly from the wrapped TokenStream
        private boolean internalNextToken() throws IOException{
            if (pendingStatePos < pendingStates.size()){
                restoreState(pendingStates.get(pendingStatePos));
                pendingStatePos ++;
                if(pendingStatePos >=pendingStates.size()){
                    pendingStatePos =0;
                    pendingStates.clear();
                }
                return true;
            }       
            if(inputExhausted) {
                return false;
            }
            return input.incrementToken();
        }

        @Override
        public boolean incrementToken() throws IOException {
            if (internalNextToken()) {
                if (nextAnnotationForInjection != null) {
                    // If we are at the right point to inject an annotation....
                    if (textOffsetAtt.startOffset() >= nextAnnotationForInjection.offset) {
                        int firstSpannedTextPosInc = posAtt.getPositionIncrement();
                        int annotationPosLen = 1;

                        // Capture the text token's state for later replay - but
                        // with a zero pos increment so is same as annotation
                        // that is injected before it
                        posAtt.setPositionIncrement(0);
                        pendingStates.add(captureState());

                        while (textOffsetAtt.endOffset() <= nextAnnotationForInjection.endOffset) {
                            // Buffer up all the other tokens spanned by this annotation to determine length.
                            if (input.incrementToken()) {
                                if (textOffsetAtt.endOffset() <= nextAnnotationForInjection.endOffset
                                        && textOffsetAtt.startOffset() < nextAnnotationForInjection.endOffset) {
                                    annotationPosLen += posAtt.getPositionIncrement();
                                }
                                pendingStates.add(captureState());
                            } else {
                                inputExhausted = true;
                                break;
                            }
                        }
                        emitAnnotation(firstSpannedTextPosInc, annotationPosLen);
                        return true;
                    }
                }
                return true;
            } else {
                inputExhausted = true;
                return false;
            }
        }
        private void setType(AnnotationToken token) {
            if (token.type != null) {
                typeAtt.setType(token.type);
            } else {
                //Default annotation type if not supplied by user
                typeAtt.setType("annotation");
            }
        }

        private void emitAnnotation(int firstSpannedTextPosInc, int annotationPosLen) throws IOException {
            // Set the annotation's attributes
            posLenAtt.setPositionLength(annotationPosLen);
            textOffsetAtt.setOffset(nextAnnotationForInjection.offset, nextAnnotationForInjection.endOffset);
            setType(nextAnnotationForInjection);
            
            // We may have multiple annotations at this location - stack them up
            final int annotationOffset = nextAnnotationForInjection.offset;
            final AnnotatedText.AnnotationToken firstAnnotationAtThisPos = nextAnnotationForInjection;
            while (nextAnnotationForInjection != null && nextAnnotationForInjection.offset == annotationOffset) {

                
                setType(nextAnnotationForInjection);
                termAtt.resizeBuffer(nextAnnotationForInjection.value.length());
                termAtt.copyBuffer(nextAnnotationForInjection.value.toCharArray(), 0, nextAnnotationForInjection.value.length());
                
                if (nextAnnotationForInjection == firstAnnotationAtThisPos) {
                    posAtt.setPositionIncrement(firstSpannedTextPosInc);
                    //Put at the head of the queue of tokens to be emitted
                    pendingStates.add(0, captureState());                
                } else {
                    posAtt.setPositionIncrement(0);                    
                    //Put after the head of the queue of tokens to be emitted
                    pendingStates.add(1, captureState());                
                }
                
                
                // Flag the inject annotation as null to prevent re-injection.
                currentAnnotationIndex++;
                if (currentAnnotationIndex < annotatedText.numAnnotations()) {
                    nextAnnotationForInjection = annotatedText.getAnnotation(currentAnnotationIndex);
                } else {
                    nextAnnotationForInjection = null;
                }
            }
            // Now pop the first of many potential buffered tokens:
            internalNextToken();
        }

      }
  

    public static final class AnnotatedTextFieldType extends StringFieldType {

        public AnnotatedTextFieldType() {
            setTokenized(true);
        }

        protected AnnotatedTextFieldType(AnnotatedTextFieldType ref) {
            super(ref);
        }
        
        @Override
        public void setIndexAnalyzer(NamedAnalyzer delegate) {
            if(delegate.analyzer() instanceof AnnotationAnalyzerWrapper){
                // Already wrapped the Analyzer with an AnnotationAnalyzer
                super.setIndexAnalyzer(delegate);
            } else {
                // Wrap the analyzer with an AnnotationAnalyzer that will inject required annotations
                super.setIndexAnalyzer(new NamedAnalyzer(delegate.name(), AnalyzerScope.INDEX,
                    new AnnotationAnalyzerWrapper(delegate.analyzer())));
            }
        }

        public AnnotatedTextFieldType clone() {
            return new AnnotatedTextFieldType(this);
        }
       

        @Override
        public String typeName() {
            return CONTENT_TYPE;
        }

        @Override
        public Query existsQuery(QueryShardContext context) {
            if (omitNorms()) {
                return new TermQuery(new Term(FieldNamesFieldMapper.NAME, name()));
            } else {
                return new NormsFieldExistsQuery(name());
            }
        }
        
        @Override
        public Query phraseQuery(String field, TokenStream stream, int slop, boolean enablePosIncrements) throws IOException {
            PhraseQuery.Builder builder = new PhraseQuery.Builder();
            builder.setSlop(slop);

            TermToBytesRefAttribute termAtt = stream.getAttribute(TermToBytesRefAttribute.class);
            PositionIncrementAttribute posIncrAtt = stream.getAttribute(PositionIncrementAttribute.class);
            int position = -1;

            stream.reset();
            while (stream.incrementToken()) {
                if (enablePosIncrements) {
                    position += posIncrAtt.getPositionIncrement();
                }
                else {
                    position += 1;
                }
                builder.add(new Term(field, termAtt.getBytesRef()), position);
            }

            return builder.build();
        }
        
        
//        @Override
//        public Query phraseQuery(String field, TokenStream stream, int slop, boolean enablePosIncrements) throws IOException {
//
//            if (indexPhrases && slop == 0 && TextFieldType.hasGaps(TextFieldType.cache(stream)) == false) {
//                stream = new FixedShingleFilter(stream, 2);
//                field = field + TextFieldMapper.FAST_PHRASE_SUFFIX;
//            }
//            PhraseQuery.Builder builder = new PhraseQuery.Builder();
//            builder.setSlop(slop);
//
//            TermToBytesRefAttribute termAtt = stream.getAttribute(TermToBytesRefAttribute.class);
//            PositionIncrementAttribute posIncrAtt = stream.getAttribute(PositionIncrementAttribute.class);
//            int position = -1;
//
//            stream.reset();
//            while (stream.incrementToken()) {
//                if (enablePosIncrements) {
//                    position += posIncrAtt.getPositionIncrement();
//                }
//                else {
//                    position += 1;
//                }
//                builder.add(new Term(field, termAtt.getBytesRef()), position);
//            }
//
//            return builder.build();
//        }
//
//        @Override
//        public Query multiPhraseQuery(String field, TokenStream stream, int slop, boolean enablePositionIncrements) throws IOException {
//
//            if (indexPhrases && slop == 0 && TextFieldType.hasGaps(TextFieldType.cache(stream)) == false) {
//                stream = new FixedShingleFilter(stream, 2);
//                field = field + TextFieldMapper.FAST_PHRASE_SUFFIX;
//            }
//
//            MultiPhraseQuery.Builder mpqb = new MultiPhraseQuery.Builder();
//            mpqb.setSlop(slop);
//
//            TermToBytesRefAttribute termAtt = stream.getAttribute(TermToBytesRefAttribute.class);
//
//            PositionIncrementAttribute posIncrAtt = stream.getAttribute(PositionIncrementAttribute.class);
//            int position = -1;
//
//            List<Term> multiTerms = new ArrayList<>();
//            stream.reset();
//            while (stream.incrementToken()) {
//                int positionIncrement = posIncrAtt.getPositionIncrement();
//
//                if (positionIncrement > 0 && multiTerms.size() > 0) {
//                    if (enablePositionIncrements) {
//                        mpqb.add(multiTerms.toArray(new Term[0]), position);
//                    } else {
//                        mpqb.add(multiTerms.toArray(new Term[0]));
//                    }
//                    multiTerms.clear();
//                }
//                position += positionIncrement;
//                multiTerms.add(new Term(field, termAtt.getBytesRef()));
//            }
//
//            if (enablePositionIncrements) {
//                mpqb.add(multiTerms.toArray(new Term[0]), position);
//            } else {
//                mpqb.add(multiTerms.toArray(new Term[0]));
//            }
//            return mpqb.build();
//        }
        

    }

    
    private int positionIncrementGap;
//    private PrefixFieldMapper prefixFieldMapper;
//    private PhraseFieldMapper phraseFieldMapper;
//
    protected AnnotatedTextFieldMapper(String simpleName, AnnotatedTextFieldType fieldType, MappedFieldType defaultFieldType,
                                int positionIncrementGap, 
                                Settings indexSettings, MultiFields multiFields, CopyTo copyTo) {
        super(simpleName, fieldType, defaultFieldType, indexSettings, multiFields, copyTo);
        assert fieldType.tokenized();
        assert fieldType.hasDocValues() == false;
        this.positionIncrementGap = positionIncrementGap;
//        this.prefixFieldMapper = prefixFieldMapper;
//        this.phraseFieldMapper = fieldType.indexPhrases ? new PhraseFieldMapper(new PhraseFieldType(fieldType), indexSettings) : null;
    }

    @Override
    protected AnnotatedTextFieldMapper clone() {
        return (AnnotatedTextFieldMapper) super.clone();
    }

    public int getPositionIncrementGap() {
        return this.positionIncrementGap;
    }

    @Override
    protected void parseCreateField(ParseContext context, List<IndexableField> fields) throws IOException {
        final String value;
        if (context.externalValueSet()) {
            value = context.externalValue().toString();
        } else {
            value = context.parser().textOrNull();
        }

        if (value == null) {
            return;
        }

        if (fieldType().indexOptions() != IndexOptions.NONE || fieldType().stored()) {
            Field field = new Field(fieldType().name(), value, fieldType());
            fields.add(field);
            if (fieldType().omitNorms()) {
                createFieldNamesField(context, fields);
            }
        }
    }

//    @Override
//    public Iterator<Mapper> iterator() {
//        List<Mapper> subIterators = new ArrayList<>();
//        if (prefixFieldMapper != null) {
//            subIterators.add(prefixFieldMapper);
//        }
//        if (phraseFieldMapper != null) {
//            subIterators.add(phraseFieldMapper);
//        }
//        if (subIterators.size() == 0) {
//            return super.iterator();
//        }
//        return Iterators.concat(super.iterator(), subIterators.iterator());
//    }

    @Override
    protected String contentType() {
        return CONTENT_TYPE;
    }

//    @Override
//    protected void doMerge(Mapper mergeWith) {
//        super.doMerge(mergeWith);
//        AnnotatedTextFieldMapper mw = (AnnotatedTextFieldMapper) mergeWith;
//        if (this.prefixFieldMapper != null && mw.prefixFieldMapper != null) {
//            this.prefixFieldMapper = (PrefixFieldMapper) this.prefixFieldMapper.merge(mw.prefixFieldMapper);
//        }
//        else if (this.prefixFieldMapper != null || mw.prefixFieldMapper != null) {
//            throw new IllegalArgumentException("mapper [" + name() + "] has different index_prefix settings, current ["
//                + this.prefixFieldMapper + "], merged [" + mw.prefixFieldMapper + "]");
//        }
//    }

    @Override
    public AnnotatedTextFieldType fieldType() {
        return (AnnotatedTextFieldType) super.fieldType();
    }

    @Override
    protected void doXContentBody(XContentBuilder builder, boolean includeDefaults, Params params) throws IOException {
        super.doXContentBody(builder, includeDefaults, params);
        doXContentAnalyzers(builder, includeDefaults);

        if (includeDefaults || positionIncrementGap != POSITION_INCREMENT_GAP_USE_ANALYZER) {
            builder.field("position_increment_gap", positionIncrementGap);
        }        
    }
}
