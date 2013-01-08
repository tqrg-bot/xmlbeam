/**
 *  Copyright 2012 Sven Ewald
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.xmlbeam;

import java.text.MessageFormat;

import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;

import java.util.HashMap;
import java.util.Map;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.transform.Transformer;
import javax.xml.xpath.XPath;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xmlbeam.annotation.XBDocURL;
import org.xmlbeam.annotation.XBRead;
import org.xmlbeam.config.DefaultXMLFactoriesConfig;
import org.xmlbeam.config.XMLFactoriesConfig;
import org.xmlbeam.io.XBFileIO;
import org.xmlbeam.io.XBStreamIO;
import org.xmlbeam.io.XBStringIO;
import org.xmlbeam.io.XBUrlIO;
import org.xmlbeam.types.DefaultTypeConverter;
import org.xmlbeam.types.TypeConverter;
import org.xmlbeam.util.ReflectionHelper;

/**
 * <p>
 * Overview<br>
 * The class XMLProjector is a tool to create, read or write so called "projections". Projections
 * are Java interfaces associated to XML documents. Projections may contain methods annotated with
 * XPath selectors. These XPath expressions define the subset of XML data which is "projected" to
 * Java values and objects.
 * </p>
 * <p>
 * Getters<br>
 * For getter methods (methods with a name-prefix "get", returning a value) the XPath-selected nodes
 * are converted to the method return type. This works with all java primitive types, Strings, and
 * lists or arrays containing primitives or Strings.
 * </p>
 * <p>
 * Setters<br>
 * Setter methods (method with a name starting with "set", having a parameter) can be defined to
 * modify the content of the associated XML document. Not all XPath capabilities define writable
 * projections, so the syntax is limited to selectors of elements and attributes. In contrast to
 * Java Beans a setter method may define a return value with the projection interface type. If this
 * return value is defined, the current projection instance is returned. This allows the definition
 * of projections according to the fluent interface pattern (aka Builder Pattern).
 * </p>
 * <p>
 * Sub Projections<br>
 * For the purpose of accessing structured data elements in the XML document you may define
 * "sub projections" which are projections associated to elements instead to documents. Sub
 * projections can be used as return type of getters and as parameters of setters. This works even
 * in arrays or lists. Because of the infamous Java type erasure you have to specify the component
 * type of the sub projection for a getter returning a list of sub projections. This type is defined
 * as second parameter "targetType" in the {@link XBRead} annotation.
 * </p>
 * <p>
 * Dynamic Projections<br>
 * XPath expressions are evaluated during runtime when the corresponding methods are called. Its
 * possible to use placeholder ("{0}, {1}, {2},... ) in the expression that will be substituted with
 * method parameters before the expression is evaluated. Therefore getters and setters may have
 * multiple parameters which will be applied via a {@link MessageFormat} to build up the final XPath
 * expression. The first parameter of a setter will be used for both, setting the document value and
 * replacing the placeholder "{0}".
 * </p>
 * <p>
 * Projection Mixins<br>
 * A mixin is defined as an object implementing a super interface of a projection. You may associate
 * a mixin with a projection type to add your own code to a projection. This way you can implement
 * validators, make a projection comparable or even share common business logic between multiple
 * projections.
 * </p>
 * 
 * @author <a href="https://github.com/SvenEwald">Sven Ewald</a>
 */
@SuppressWarnings("serial")
public class XBProjector implements Serializable {

    /**
     * A variation of the builder pattern. All methods to configure the projector are hidden in this
     * builder class.
     */
    public class ConfigBuilder {
             
        public DocumentBuilder getDocumentBuilder() {
            return xMLFactoriesConfig.createDocumentBuilder();
        }

        public Transformer getTransformer() {
            return xMLFactoriesConfig.createTransformer();
        }

        public TypeConverter getTypeConverter() {
            return XBProjector.this.typeConverter;
        }

        /**
         * @return
         */
        public XPath getXPath(Document document) {
            return xMLFactoriesConfig.createXPath(document);
        }

        public ConfigBuilder setTypeConverter(TypeConverter converter) {
            XBProjector.this.typeConverter = converter;
            return this;
        }
    }

    /**
     * A variation of the builder pattern. Mixin related methods are grouped behind this builder
     * class.
     */
    public class MixinBuilder {
        /**
         * Register a new mixin for a projection interface. By letting a projection extend another
         * interface you are able to add custom behavior to projections by registering an
         * implementation (called a mixin) of this interface here. A mixin is registered per
         * projection type. Only one mixin implementation per projection and mixin type is possible.
         * All existing and all future projection instances will change. Notice that you will break
         * projection serialization if you register a non serializeable mixin.
         * 
         * @param projectionInterface
         * @param mixinImplementation
         * @return
         */
        public <S, M extends S, P extends S> XBProjector addProjectionMixin(Class<P> projectionInterface, M mixinImplementation) {
            if (!isValidProjectionInterface(projectionInterface)) {
                throw new IllegalArgumentException("Parameter " + projectionInterface + " is not a public interface.");
            }
            Map<Class<?>, Object> map = mixins.containsKey(projectionInterface) ? mixins.get(projectionInterface) : new HashMap<Class<?>, Object>();
            for (Class<?> type : ReflectionHelper.findAllCommonSuperInterfaces(projectionInterface, mixinImplementation.getClass())) {
                map.put(type, mixinImplementation);
            }
            mixins.put(projectionInterface, map);
            return XBProjector.this;

        }

        /**
         * Get the mixin implementation registered for the given projection.
         * 
         * @param projectionInterface
         * @param mixinInterface
         * @return the registered mixin implementation. null if none is present.
         */
        public Object getProjectionMixin(Class<?> projectionInterface, Class<?> mixinInterface) {
            if (!mixins.containsKey(projectionInterface)) {
                return null;
            }
            return mixins.get(projectionInterface).get(mixinInterface);
        }

        /**
         * Remove the mixin implementation registered for the given projection.
         * 
         * @param projectionInterface
         * @param mixinInterface
         * @return the registered mixin implementation. null if none was present.
         */
        @SuppressWarnings("unchecked")
        public <M, P extends M> M removeProjectionMixin(Class<P> projectionInterface, Class<M> mixinInterface) {
            if (!mixins.containsKey(projectionInterface)) {
                return null;
            }
            return (M) mixins.get(projectionInterface).remove(mixinInterface);
        }

    }

    public class ReaderBuilder {
        /**
         * Create a new projection by parsing given file.
         * 
         * @param file
         * @param projectionInterface
         *            A Java interface to project the data on.
         * @return
         * @throws IOException
         */
        public <T> T fromFile(final File file, final Class<T> projectionInterface) throws IOException {
            return new XBFileIO(XBProjector.this).read(file, projectionInterface);
        }

        /**
         * Create a new projection by parsing the data provided by the input stream.
         * 
         * @param is
         * @param projectionInterface
         *            A Java interface to project the data on.
         * @return
         * @throws IOException
         */
        public <T> T fromInputStream(final InputStream is, final Class<T> projectionInterface) throws IOException {
            return new XBStreamIO(XBProjector.this).read(is, projectionInterface);
        }

        /**
         * Create a new projection using a given uri parameter. When the uri starts with the
         * protocol identifier "resource://" the classloader of projection interface will be used to
         * read the resource from the current class path.
         * 
         * @param uri
         * @param projectionInterface
         *            A Java interface to project the data on.
         * @return a new projection instance.
         * @throws IOException
         */
        public <T> T fromURL(final String uri, final Class<T> projectionInterface) throws IOException {

            return new XBUrlIO(XBProjector.this).getFromURL(uri, projectionInterface);
        }

        /**
         * Create a new projection using a {@link XBDocURL} annotation on this interface. When the XBDocURL
         * starts with the protocol identifier "resource://" the class loader of the projection
         * interface will be used to read the resource from the current class path.
         * 
         * @param projectionInterface
         *            a public interface.
         * @return a new projection instance
         * @throws IOException
         */
        public <T> T fromURLAnnotation(final Class<T> projectionInterface) throws IOException {
            org.xmlbeam.annotation.XBDocURL doc = projectionInterface.getAnnotation(org.xmlbeam.annotation.XBDocURL.class);
            if (doc == null) {
                throw new IllegalArgumentException("Class " + projectionInterface.getCanonicalName() + " must have the " + XBDocURL.class.getName() + " annotation linking to the document source.");
            }
            return (fromURL(doc.value(), projectionInterface));
        }
        
        /**
         * Takes a string with XML content and projects it to the given projection interface.
         * @param xmlString
         * @param projectionInterface
         * @return
         * @throws IOException
         */
        public <T> T parseString(final String xmlString, final Class<T> projectionInterface) {
            return new XBStringIO(XBProjector.this).parseXMLString(xmlString, projectionInterface);
        }
    }

    public class WriterBuilder {
        /**
         * Write the projected document to a file.
         * 
         * @param projection
         * @param file
         * @return
         * @throws IOException
         */
        public XBProjector toFile(Object projection, final File file) throws IOException {
            new XBFileIO(XBProjector.this).write(projection, file);
            return XBProjector.this;
        }

        public XBProjector toOutputStream(Object projection, final OutputStream os) throws IOException {
            new XBStreamIO(XBProjector.this).write(projection, os);
            return XBProjector.this;
        }

        /**
         * Write projected document to url (file or http post) of {@link XBDocURL} annotation.
         * 
         * @param projection
         * @return response of http post or null for file urls.
         * @throws IOException
         */
        public String toURLAnnotationViaPOST(final Object projection) throws IOException {
            Class<?> projectionInterface = checkProjectionInstance(projection).getProjectionInterface();
            org.xmlbeam.annotation.XBDocURL doc = projectionInterface.getAnnotation(org.xmlbeam.annotation.XBDocURL.class);
            if (doc == null) {
                throw new IllegalArgumentException("Class " + projectionInterface.getCanonicalName() + " must have the " + XBDocURL.class.getName() + " annotation linking to the document source.");
            }
            String url = doc.value();
            if ((url.startsWith("http://")) || (url.startsWith("https://"))) {
                return postToURL(projection, url, null);
            }
            if (url.startsWith("file://")) {
                File file = new File(url.substring("file://".length()));
                new XBFileIO(XBProjector.this).write(projection, file);
                return null;
            }
            throw new IllegalArgumentException("I don't know how to write to url:" + url + " Try again with a http or file url.");
        }

        /**
         * Post the projected document to a http url. The response is provided as a raw string.
         * 
         * @param projection
         * @param httpurl
         * @return response as String
         * @throws IOException
         */
        public String postToURL(Object projection, String httpurl, Map<String, String> additionalRequestParams) throws IOException {
            return new XBUrlIO(XBProjector.this).addRequestParams(additionalRequestParams).postToURL(projection, httpurl);
        }
    }

    /**
     * Marker interface to determine if a Projection instance was created by a Projector. This will
     * be applied automatically to projections.
     */
    interface Projection extends Serializable {

        Class<?> getProjectionInterface();

        Node getXMLNode();
    }

    private final XMLFactoriesConfig xMLFactoriesConfig;

    private final Map<Class<?>, Map<Class<?>, Object>> mixins = new HashMap<Class<?>, Map<Class<?>, Object>>();

    private TypeConverter typeConverter = new DefaultTypeConverter();

    /**
     * Constructor. Use me to create a projector with defaults.
     */
    public XBProjector() {
        xMLFactoriesConfig =  new DefaultXMLFactoriesConfig();
    }

    /**
     * @param xMLFactoriesConfig
     */
    public XBProjector(XMLFactoriesConfig xMLFactoriesConfig) {
        this.xMLFactoriesConfig = xMLFactoriesConfig;
    }

    /**
     * Shortcut for creating a {@link ConfigBuilder} object to change the projectors configuration.
     * 
     * @return a new ConfigBuilder for this projector.
     */
    public ConfigBuilder config() {
        return new ConfigBuilder();
    }

    /**
     * Shortcut for creating a {@link MixinBuilder} object add or remove mixins to projections.
     * 
     * @return a new MixinBuilder for this projector.
     */
    public MixinBuilder mixins() {
        return new MixinBuilder();
    }

    /**
     * Create a new projection for an empty document. Use this to create new documents.
     * 
     * @param projectionInterface
     * @return a new projection instance
     */
    public <T> T createEmptyDocumentProjection(Class<T> projectionInterface) {
        Document document = xMLFactoriesConfig.createDocumentBuilder().newDocument();
        return projectXML(document, projectionInterface);
    }

    /**
     * Create a new projection for an empty element. Use this to create new elements.
     * 
     * @param name Element name
     * @param projectionInterface 
     * @return a new projection instance
     */
    public <T> T createEmptyElementProjection(final String name,Class<T> projectionInterface) {
        Document document = xMLFactoriesConfig.createDocumentBuilder().newDocument();
        Element element = document.createElement(name);
        return projectXML(element, projectionInterface);
    }

    /**
     * Use this method to obtain the DOM tree behind a projection. Changing the DOM of a projection
     * is a valid action and may change the results of the projections methods.
     * 
     * @param projection
     * @return Document holding projections data.
     */
    public Document getXMLDocForProjection(final Object projection) {
        Node node = checkProjectionInstance(projection).getXMLNode();
        if (Node.DOCUMENT_NODE == node.getNodeType()) {
            return (Document) node;
        }
        return node.getOwnerDocument();
    }

    /**
     * Ensures that the given object is a projection created by a projector.
     * 
     * @param projection
     * @return
     */
    private Projection checkProjectionInstance(Object projection) {
        if (!(projection instanceof Projection)) {
            throw new IllegalArgumentException("Given projection " + projection + " was not created by me.");
        }
        return (Projection) projection;
    }

    /**
     * @param projectionInterface
     * @return true if param is a public interface.
     */
    private <T> boolean isValidProjectionInterface(final Class<T> projectionInterface) {
        return (projectionInterface != null) && (projectionInterface.isInterface()) && ((projectionInterface.getModifiers() & Modifier.PUBLIC) == Modifier.PUBLIC);
    }

    /**
     * Creates a projection from XML to Java.
     * 
     * @param node
     *            XML DOM Node. May be a document or just an element.
     * @param projectionInterface
     *            A Java interface to project the data on.
     * @return a new instance of projectionInterface.
     */
    @SuppressWarnings("unchecked")
    public <T> T projectXML(final Node node, final Class<T> projectionInterface) {
        if (!isValidProjectionInterface(projectionInterface)) {
            throw new IllegalArgumentException("Parameter " + projectionInterface + " is not a public interface.");
        }
        return ((T) Proxy.newProxyInstance(projectionInterface.getClassLoader(), new Class[] { projectionInterface, Projection.class, Serializable.class }, new ProjectionInvocationHandler(this, node, projectionInterface)));
    }

    /**
     * 
     * @return 
     */
    public ReaderBuilder read() {
        return new ReaderBuilder();
    }

    public WriterBuilder write() {
        return new WriterBuilder();
    }
}
