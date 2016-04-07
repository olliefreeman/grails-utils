package uk.ac.ox.ndm.grails.utils.databinding

import com.google.common.base.CaseFormat
import grails.databinding.CollectionDataBindingSource
import grails.databinding.DataBindingSource
import grails.databinding.SimpleMapDataBindingSource
import grails.util.Holders
import grails.web.mime.MimeType
import grails.web.servlet.mvc.GrailsParameterMap
import groovy.transform.CompileStatic
import groovy.transform.TypeChecked
import groovy.transform.TypeCheckingMode
import groovy.util.slurpersupport.GPathResult
import org.grails.databinding.bindingsource.DataBindingSourceCreationException
import org.grails.databinding.xml.GPathResultCollectionDataBindingSource
import org.grails.databinding.xml.GPathResultMap
import org.grails.io.support.SpringIOUtils
import org.grails.web.databinding.bindingsource.DefaultDataBindingSourceCreator
import org.grails.web.databinding.bindingsource.InvalidRequestBodyException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.xml.sax.SAXParseException
import uk.ac.ox.ndm.grails.utils.Utils
import uk.ac.ox.ndm.grails.utils.databinding.bindingsource.DataTypeDataBindingSource
import uk.ac.ox.ndm.grails.utils.domain.DataType
import uk.ac.ox.ndm.grails.utils.serializer.SerializeMappings

import javax.servlet.http.HttpServletRequest
import java.lang.annotation.Annotation
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType

/**
 * @since 02/09/2015
 */
@CompileStatic
class CaseAdjustingXmlDataBindingSourceCreator extends DefaultDataBindingSourceCreator implements Utils {

    private static final Logger logger = LoggerFactory.getLogger(CaseAdjustingXmlDataBindingSourceCreator)

    @Autowired
    ApplicationContext applicationContext

    private Collection<XmlDataBindingSourceCreatorHelper> postProcessors

    @Override
    MimeType[] getMimeTypes() {
        [MimeType.XML, MimeType.TEXT_XML] as MimeType[]
    }

    protected boolean initialised

    CaseAdjustingXmlDataBindingSourceCreator() {
        initialised = false
        postProcessors = []
    }

    CaseAdjustingXmlDataBindingSourceCreator(ApplicationContext applicationContext) {
        this()
        this.applicationContext = applicationContext
        initialise()
    }

    CaseAdjustingXmlDataBindingSourceCreator(Collection<XmlDataBindingSourceCreatorHelper> postProcessors) {
        this()
        initialise postProcessors
    }

    void initialise() {
        if (!applicationContext) applicationContext = Holders.getApplicationContext()
        initialise applicationContext.getBeansOfType(XmlDataBindingSourceCreatorHelper).values()
    }

    void initialise(XmlDataBindingSourceCreatorHelper postProcessor) {
        initialise([postProcessor])
    }

    void initialise(Collection<XmlDataBindingSourceCreatorHelper> postProcessors) {
        this.postProcessors = postProcessors
        initialised = true
    }


    // Collection value data binding processing

    @Override
    CollectionDataBindingSource createCollectionDataBindingSource(MimeType mimeType, Class bindingTargetType,
                                                                  Object bindingSource) {
        if (bindingSource instanceof GPathResult) {
            new GPathResultCollectionDataBindingSource(bindingSource)
        }
        else {
            try {
                if (bindingSource instanceof GrailsParameterMap) {
                    def req = bindingSource.getRequest()
                    def is = req.getInputStream()
                    return createCollectionBindingSource(is, req.getCharacterEncoding())
                }
                if (bindingSource instanceof HttpServletRequest) {
                    def req = bindingSource as HttpServletRequest
                    def is = req.getInputStream()
                    return createCollectionBindingSource(is, req.getCharacterEncoding())
                }
                if (bindingSource instanceof InputStream) {
                    def is = bindingSource as InputStream
                    return createCollectionBindingSource(is, "UTF-8")
                }
                if (bindingSource instanceof Reader) {
                    def is = bindingSource as Reader
                    return createCollectionBindingSource(is)
                }

                return super.createCollectionDataBindingSource(mimeType, bindingTargetType, bindingSource)
            } catch (Exception e) {
                throw new DataBindingSourceCreationException(e)
            }
        }
    }

    CollectionDataBindingSource createCollectionBindingSource(InputStream inputStream, String charsetName) {
        createCollectionBindingSource(new InputStreamReader(inputStream, charsetName ?: 'UTF-8'))
    }

    CollectionDataBindingSource createCollectionBindingSource(Reader reader) {
        new GPathResultCollectionDataBindingSource(SpringIOUtils.createXmlSlurper().parse(reader))
    }

    // Single value data binding processing

    @Override
    DataBindingSource createDataBindingSource(MimeType mimeType, Class bindingTargetType, Object bindingSource) {
        try {
            if (bindingSource instanceof String) {
                GPathResult result = new XmlSlurper().parseText(new String(bindingSource))
                return createDataBindingSource(result, bindingTargetType)
            }
            if (bindingSource instanceof GPathResult) {
                return createDataBindingSource(bindingSource, bindingTargetType)
            }
            if (bindingSource instanceof HttpServletRequest) {
                def req = bindingSource as HttpServletRequest
                def is = req.getInputStream()
                return createDataBindingSource(is, req.getCharacterEncoding(), bindingTargetType)
            }
            if (bindingSource instanceof InputStream) {
                def is = bindingSource as InputStream
                return createDataBindingSource(is, "UTF-8", bindingTargetType)
            }
            if (bindingSource instanceof Reader) {
                def is = bindingSource as Reader
                return createDataBindingSource(is, bindingTargetType)
            }
            return super.createDataBindingSource(mimeType, bindingTargetType, bindingSource)
        } catch (Exception e) {
            throw createBindingSourceCreationException(e)
        }
    }

    DataBindingSource createDataBindingSource(InputStream inputStream, String charsetName, Class bindingTargetType) {
        createDataBindingSource(new InputStreamReader(inputStream, charsetName ?: 'UTF-8'), bindingTargetType)
    }

    DataBindingSource createDataBindingSource(Reader reader, Class bindingTargetType) {
        createDataBindingSource(SpringIOUtils.createXmlSlurper().parse(reader), bindingTargetType)
    }

    DataBindingSource createDataBindingSource(GPathResult gPathResult, Class bindingTargetType) {
        createDataBindingSource(new GPathResultMap(gPathResult), bindingTargetType)
    }

    DataBindingSource createDataBindingSource(Map<String, ?> input, Class bindingTargetType) {
        Map<String, ?> preprocessed = preProcessDataBindingMap(input)
        Object processed = processDataBindingMap(preprocessed, bindingTargetType)
        if (processed instanceof Map) return new SimpleMapDataBindingSource(processed)
        if (processed instanceof DataBindingSource) return processed as DataBindingSource

        throw new InvalidClassException('Processed value class ' + processed.class.canonicalName +
                                        ' is not Map or DataBindingSource')
    }

    Object processDataBindingMap(Map<String, ?> input, Class bindingTargetType) {

        Annotation mappings = bindingTargetType.getAnnotation(SerializeMappings) ?:
                              bindingTargetType.superclass?.getAnnotation(SerializeMappings)

        Map<String, Object> keyMappings = extractNameMappings(mappings?.nameMappings())

        Map<String, Object> processedDataBindingMap = [:]

        // Convert the input map into a valid databinding map
        input.each {k, v ->
            Object validKey = createValidKey(k, keyMappings)
            if (validKey instanceof Map) {
                Map<String, ?> keyValue = extractKeyValuePairFromKeyMapping(validKey as Map<String, String>, v)

                keyValue.each {mappedKey, mappedValue ->
                    Object value = convertValue(mappedValue, mappedKey, bindingTargetType, processedDataBindingMap)
                    processedDataBindingMap.put(mappedKey, value)
                }
            }
            else {
                Object value = convertValue(v, validKey as String, bindingTargetType, processedDataBindingMap)
                processedDataBindingMap.put(validKey as String, value)
            }

        }

        checkProcessDataBindingMap(processedDataBindingMap, bindingTargetType)

    }


    Object convertValue(Object v, String key, Class bindingTargetType, Map<String, ?> converted) {
        def value = v
        if (v instanceof List) {
            value = converted.getOrDefault(key, [])
            v.each {
                value = convertAndAddToList(key, it, bindingTargetType, value as List)
            }
        }
        else {
            Class bindingType = determineBindingType(bindingTargetType, key)
            if (v instanceof Map) {
                if (!bindingType) {
                    throw new IllegalStateException(
                            "There must be a property in " + bindingTargetType.canonicalName + " for key " + key)
                }

                if (Collection.isAssignableFrom(bindingType)) {
                    return convertAndAddToList(key, value, bindingTargetType, converted.getOrDefault(key, []) as List)
                }
                value = processDataBindingMap(value as Map<String, ?>, bindingType)
            }
            else if (bindingType && DataType.isAssignableFrom(bindingType)) {
                value = new DataTypeDataBindingSource([key: value], bindingType)
            }
        }
        value
    }

    @TypeChecked(TypeCheckingMode.SKIP)
    Class determineBindingType(Class bindingTargetType, String key) {
        MetaProperty metaProperty = bindingTargetType.metaClass.getMetaProperty(key)
        if (!metaProperty && bindingTargetType.superclass != Object) {
            return determineBindingType(bindingTargetType.superclass, key)
        }

        def bindingType = metaProperty?.field?.type ?: metaProperty?.type
        if (bindingType == Object && bindingTargetType.superclass != Object) {
            return determineBindingType(bindingTargetType.superclass, key)
        }
        if (bindingType == Object) {
            String methodSetterName = "set${CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_CAMEL, key)}".toString()
            Method setter = bindingTargetType.methods.find {it.name == methodSetterName}
            if (setter) return setter.parameterTypes[0]
        }
        bindingType
    }

    Object convertAndAddToList(String key, Object value, Class bindingTargetType, List listValue) {

        if (value instanceof Map) {
            def bindingType = getReferencedTypeForCollectionInClass(key, bindingTargetType)
            if (!bindingType) {
                throw new IllegalStateException("There must be a binding type in " + bindingTargetType.canonicalName + " for key "
                                                        + key)
            }
            listValue += processDataBindingMap(value, bindingType)

        }
        else listValue += value

        listValue
    }

    static Map<String, ?> extractKeyValuePairFromKeyMapping(Map<String, String> keys, Object value) {

        if (!value) return [:]
        Map<String, ?> result = [:]

        keys.each {key, mapping ->

            if (value[key]) {
                if (mapping instanceof Map) {
                    result.putAll(extractKeyValuePairFromKeyMapping(mapping as Map<String, String>, value[key]))

                }
                else result[mapping] = value[key]
            }

        }

        result
    }

    static Map<String, ?> preProcessDataBindingMap(Map<String, ?> map) {
        if (map == null) throw new IllegalArgumentException('null map is not permitted for pre processing')
        map.collectEntries {k, v ->
            [(createValidKey(k, [:])): (preProcessDataBindingValue(v))]
        } as Map<String, ?>
    }

    Object checkProcessDataBindingMap(Map<String, ?> processDataBindingMap, Class bindingTargetType) {

        if (!initialised) initialise()

        for (XmlDataBindingSourceCreatorHelper postProcessor : postProcessors) {
            def obj = postProcessor.checkDataBindingSourceMap(processDataBindingMap, bindingTargetType)
            if (obj) return obj
        }
        processDataBindingMap
    }

    static Object preProcessDataBindingValue(Object obj) {
        obj instanceof Map ?
        preProcessDataBindingMap(obj) :
        obj instanceof Collection ?
        obj.collect {preProcessDataBindingValue(it)} :
        obj
    }

    static Object createValidKey(String k, Map keyMappings) {
        String key = k.contains('-') ? CaseFormat.LOWER_HYPHEN.to(CaseFormat.LOWER_CAMEL, k) :
                     CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_CAMEL, k)
        keyMappings ? (key in keyMappings.keySet() ? keyMappings[key] : key) : key
    }

    static Class<?> getReferencedTypeForCollectionInClass(String propertyName, Class clazz) {
        def field = getField(clazz, propertyName)
        if (field) {
            def genericType = field.genericType
            if (genericType instanceof ParameterizedType) {
                return Map.isAssignableFrom(genericType.getRawType() as Class<?>) ?
                       genericType.getActualTypeArguments()[1] : genericType.getActualTypeArguments()[0]
            }
        }
        null
    }

    static Field getField(Class clazz, String fieldName) {
        try {
            return clazz.getDeclaredField(fieldName)
        } catch (NoSuchFieldException ignored) {
            return clazz.getSuperclass() != Object ? getField(clazz.getSuperclass(), fieldName) : null
        }
    }

    static DataBindingSourceCreationException createBindingSourceCreationException(Exception e) {
        logger.error "Exception while creating databinding source: {}", e.message
        if (e instanceof SAXParseException) {
            return new InvalidRequestBodyException(e)
        }
        e.printStackTrace()
        return new DataBindingSourceCreationException(e)
    }
}
