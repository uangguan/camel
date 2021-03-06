/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.tools.apt;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import javax.annotation.processing.Filer;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

import org.apache.camel.tooling.util.Strings;

import static org.apache.camel.tooling.util.Strings.canonicalClassName;
import static org.apache.camel.tooling.util.Strings.isNullOrEmpty;

/**
 * Abstract class for Camel apt plugins.
 */
public final class AnnotationProcessorHelper {

    private static final String VALID_CHARS = ".,-='/\\!&%():;#${}";

    private AnnotationProcessorHelper() {
    }

    public static String findJavaDoc(Elements elementUtils, Element element, String fieldName, String name, TypeElement classElement, boolean builderPattern) {
        String answer = null;
        if (element != null) {
            answer = elementUtils.getDocComment(element);
        }
        if (isNullOrEmpty(answer)) {
            ExecutableElement setter = findSetter(fieldName, classElement);
            if (setter != null) {
                String doc = elementUtils.getDocComment(setter);
                if (!isNullOrEmpty(doc)) {
                    answer = doc;
                }
            }

            // lets find the getter
            if (answer == null) {
                ExecutableElement getter = findGetter(fieldName, classElement);
                if (getter != null) {
                    String doc = elementUtils.getDocComment(getter);
                    if (!isNullOrEmpty(doc)) {
                        answer = doc;
                    }
                }
            }

            // lets try builder pattern
            if (answer == null && builderPattern) {
                List<ExecutableElement> methods = ElementFilter.methodsIn(classElement.getEnclosedElements());
                // lets try the builder pattern using annotation name (optional) as the method name
                if (name != null) {
                    for (ExecutableElement method : methods) {
                        String methodName = method.getSimpleName().toString();
                        if (name.equals(methodName) && method.getParameters().size() == 1) {
                            String doc = elementUtils.getDocComment(method);
                            if (!isNullOrEmpty(doc)) {
                                answer = doc;
                                break;
                            }
                        }
                    }
                    // there may be builder pattern with no-parameter methods, such as more common for boolean types
                    // so lets try those as well
                    for (ExecutableElement method : methods) {
                        String methodName = method.getSimpleName().toString();
                        if (name.equals(methodName) && method.getParameters().size() == 0) {
                            String doc = elementUtils.getDocComment(method);
                            if (!isNullOrEmpty(doc)) {
                                answer = doc;
                                break;
                            }
                        }
                    }
                }
                // lets try builder pattern using fieldName as the method name
                for (ExecutableElement method : methods) {
                    String methodName = method.getSimpleName().toString();
                    if (fieldName.equals(methodName) && method.getParameters().size() == 1) {
                        String doc = elementUtils.getDocComment(method);
                        if (!isNullOrEmpty(doc)) {
                            answer = doc;
                            break;
                        }
                    }
                }
                // there may be builder pattern with no-parameter methods, such as more common for boolean types
                // so lets try those as well
                for (ExecutableElement method : methods) {
                    String methodName = method.getSimpleName().toString();
                    if (fieldName.equals(methodName) && method.getParameters().size() == 0) {
                        String doc = elementUtils.getDocComment(method);
                        if (!isNullOrEmpty(doc)) {
                            answer = doc;
                            break;
                        }
                    }
                }
            }
        }
        return answer;
    }

    public static ExecutableElement findSetter(String fieldName, TypeElement classElement) {
        String setter = "set" + fieldName.substring(0, 1).toUpperCase();
        if (fieldName.length() > 1) {
            setter += fieldName.substring(1);
        }
        //  lets find the setter
        List<ExecutableElement> methods = ElementFilter.methodsIn(classElement.getEnclosedElements());
        for (ExecutableElement method : methods) {
            String methodName = method.getSimpleName().toString();
            if (setter.equals(methodName) && method.getParameters().size() == 1 && method.getReturnType().getKind().equals(TypeKind.VOID)) {
                return method;
            }
        }

        return null;
    }

    public static ExecutableElement findGetter(String fieldName, TypeElement classElement) {
        String getter1 = "get" + fieldName.substring(0, 1).toUpperCase();
        if (fieldName.length() > 1) {
            getter1 += fieldName.substring(1);
        }
        String getter2 = "is" + fieldName.substring(0, 1).toUpperCase();
        if (fieldName.length() > 1) {
            getter2 += fieldName.substring(1);
        }
        //  lets find the getter
        List<ExecutableElement> methods = ElementFilter.methodsIn(classElement.getEnclosedElements());
        for (ExecutableElement method : methods) {
            String methodName = method.getSimpleName().toString();
            if ((getter1.equals(methodName) || getter2.equals(methodName)) && method.getParameters().size() == 0) {
                return method;
            }
        }

        return null;
    }

    public static VariableElement findFieldElement(TypeElement classElement, String fieldName) {
        if (isNullOrEmpty(fieldName)) {
            return null;
        }

        List<VariableElement> fields = ElementFilter.fieldsIn(classElement.getEnclosedElements());
        for (VariableElement field : fields) {
            if (fieldName.equals(field.getSimpleName().toString())) {
                return field;
            }
        }

        return null;
    }

    public static TypeElement findTypeElement(ProcessingEnvironment processingEnv, RoundEnvironment roundEnv, String className) {
        if (isNullOrEmpty(className) || "java.lang.Object".equals(className)) {
            return null;
        }

        Set<? extends Element> rootElements = roundEnv.getRootElements();
        for (Element rootElement : rootElements) {
            if (rootElement instanceof TypeElement) {
                TypeElement typeElement = (TypeElement) rootElement;
                String aRootName = canonicalClassName(typeElement.getQualifiedName().toString());
                if (className.equals(aRootName)) {
                    return typeElement;
                }
            }
        }

        // fallback using package name
        Elements elementUtils = processingEnv.getElementUtils();

        int idx = className.lastIndexOf('.');
        if (idx > 0) {
            String packageName = className.substring(0, idx);
            PackageElement pe = elementUtils.getPackageElement(packageName);
            if (pe != null) {
                List<? extends Element> enclosedElements = getEnclosedElements(pe);
                for (Element rootElement : enclosedElements) {
                    if (rootElement instanceof TypeElement) {
                        TypeElement typeElement = (TypeElement) rootElement;
                        String aRootName = canonicalClassName(typeElement.getQualifiedName().toString());
                        if (className.equals(aRootName)) {
                            return typeElement;
                        }
                    }
                }
            }
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    public static List<? extends Element> getEnclosedElements(PackageElement pe) {
        // some components like hadoop/spark has bad classes that causes javac scanning issues
        try {
            return pe.getEnclosedElements();
        } catch (Throwable e) {
            // ignore
        }
        return Collections.EMPTY_LIST;
    }

    public static void findTypeElementChildren(ProcessingEnvironment processingEnv, RoundEnvironment roundEnv, Set<TypeElement> found, String superClassName) {
        Elements elementUtils = processingEnv.getElementUtils();

        int idx = superClassName.lastIndexOf('.');
        if (idx > 0) {
            String packageName = superClassName.substring(0, idx);
            PackageElement pe = elementUtils.getPackageElement(packageName);
            if (pe != null) {
                List<? extends Element> enclosedElements = pe.getEnclosedElements();
                for (Element rootElement : enclosedElements) {
                    if (rootElement instanceof TypeElement) {
                        TypeElement typeElement = (TypeElement) rootElement;
                        String aSuperClassName = canonicalClassName(typeElement.getSuperclass().toString());
                        if (superClassName.equals(aSuperClassName)) {
                            found.add(typeElement);
                        }
                    }
                }
            }
        }
    }

    public static boolean hasSuperClass(ProcessingEnvironment processingEnv, RoundEnvironment roundEnv, TypeElement classElement, String superClassName) {
        String aRootName = canonicalClassName(classElement.getQualifiedName().toString());

        // do not check the classes from JDK itself
        if (isNullOrEmpty(aRootName) || aRootName.startsWith("java.") || aRootName.startsWith("javax.")) {
            return false;
        }

        String aSuperClassName = canonicalClassName(classElement.getSuperclass().toString());
        if (superClassName.equals(aSuperClassName)) {
            return true;
        }

        TypeElement aSuperClass = findTypeElement(processingEnv, roundEnv, aSuperClassName);
        if (aSuperClass != null) {
            return hasSuperClass(processingEnv, roundEnv, aSuperClass, superClassName);
        } else {
            return false;
        }
    }

    public static boolean implementsInterface(ProcessingEnvironment processingEnv, RoundEnvironment roundEnv, TypeElement classElement, String interfaceClassName) {
        while (true) {
            // check if the class implements the interface
            List<? extends TypeMirror> list = classElement.getInterfaces();
            if (list != null) {
                for (TypeMirror type : list) {
                    if (type.getKind().compareTo(TypeKind.DECLARED) == 0) {
                        String name = type.toString();
                        if (interfaceClassName.equals(name)) {
                            return true;
                        }
                    }
                }
            }

            // check super classes which may implement the interface
            TypeElement baseTypeElement = null;
            TypeMirror superclass = classElement.getSuperclass();
            if (superclass != null) {
                String superClassName = canonicalClassName(superclass.toString());
                baseTypeElement = findTypeElement(processingEnv, roundEnv, superClassName);
            }
            if (baseTypeElement != null) {
                classElement = baseTypeElement;
            } else {
                break;
            }
        }

        return false;
    }

    /**
     * Helper method to produce class output text file using the given handler
     */
    public static void processFile(ProcessingEnvironment processingEnv, String packageName, String fileName, Consumer<PrintWriter> handler) {
        try {
            Filer filer = processingEnv.getFiler();
            FileObject resource = filer.createResource(StandardLocation.CLASS_OUTPUT, packageName, fileName);
            log(processingEnv, "Writing file: " + packageName + "/" + fileName);
            try (Writer w = resource.openWriter(); PrintWriter writer = new PrintWriter(w)) {
                handler.accept(writer);
            }
        } catch (IOException e) {
            log(processingEnv, e);
        }
    }

    public static void log(ProcessingEnvironment processingEnv, String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, message);
    }

    public static void error(ProcessingEnvironment processingEnv, String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message);
    }

    public static void log(ProcessingEnvironment processingEnv, Throwable e) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, e.getMessage());
        StringWriter buffer = new StringWriter();
        PrintWriter writer = new PrintWriter(buffer);
        e.printStackTrace(writer);
        writer.close();
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, buffer.toString());
    }

    public static void dumpExceptionToErrorFile(String fileName, String message, Throwable e) {
        try (BufferedWriter w = Files.newBufferedWriter(Paths.get(fileName), StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            w.append(message);
            w.append("\n\n");
            PrintWriter pw = new PrintWriter(w);
            e.printStackTrace(pw);
            pw.flush();
        } catch (Throwable t) {
            // ignore
        }
    }

    /**
     * Gets the JSon schema type.
     *
     * @param   type the java type
     * @return  the json schema type, is never null, but returns <tt>object</tt> as the generic type
     */
    public static String getType(String type, boolean enumType) {
        if (enumType) {
            return "enum";
        } else if (type == null) {
            // return generic type for unknown type
            return "object";
        } else if (type.equals(URI.class.getName()) || type.equals(URL.class.getName())) {
            return "string";
        } else if (type.equals(File.class.getName())) {
            return "string";
        } else if (type.equals(Date.class.getName())) {
            return "string";
        } else if (type.startsWith("java.lang.Class")) {
            return "string";
        } else if (type.startsWith("java.util.List") || type.startsWith("java.util.Collection")) {
            return "array";
        }

        String primitive = getPrimitiveType(type);
        if (primitive != null) {
            return primitive;
        }

        return "object";
    }

    /**
     * Gets the JSon schema primitive type.
     *
     * @param   name the java type
     * @return  the json schema primitive type, or <tt>null</tt> if not a primitive
     */
    public static String getPrimitiveType(String name) {
        // special for byte[] or Object[] as its common to use
        if ("java.lang.byte[]".equals(name) || "byte[]".equals(name)) {
            return "string";
        } else if ("java.lang.Byte[]".equals(name) || "Byte[]".equals(name)) {
            return "array";
        } else if ("java.lang.Object[]".equals(name) || "Object[]".equals(name)) {
            return "array";
        } else if ("java.lang.String[]".equals(name) || "String[]".equals(name)) {
            return "array";
        } else if ("java.lang.Character".equals(name) || "Character".equals(name) || "char".equals(name)) {
            return "string";
        } else if ("java.lang.String".equals(name) || "String".equals(name)) {
            return "string";
        } else if ("java.lang.Boolean".equals(name) || "Boolean".equals(name) || "boolean".equals(name)) {
            return "boolean";
        } else if ("java.lang.Integer".equals(name) || "Integer".equals(name) || "int".equals(name)) {
            return "integer";
        } else if ("java.lang.Long".equals(name) || "Long".equals(name) || "long".equals(name)) {
            return "integer";
        } else if ("java.lang.Short".equals(name) || "Short".equals(name) || "short".equals(name)) {
            return "integer";
        } else if ("java.lang.Byte".equals(name) || "Byte".equals(name) || "byte".equals(name)) {
            return "integer";
        } else if ("java.lang.Float".equals(name) || "Float".equals(name) || "float".equals(name)) {
            return "number";
        } else if ("java.lang.Double".equals(name) || "Double".equals(name) || "double".equals(name)) {
            return "number";
        }

        return null;
    }

    /**
     * Sanitizes the javadoc to removed invalid characters so it can be used as json description
     *
     * @param javadoc  the javadoc
     * @return the text that is valid as json
     */
    public static String sanitizeDescription(String javadoc, boolean summary) {
        if (isNullOrEmpty(javadoc)) {
            return null;
        }

        // lets just use what java accepts as identifiers
        StringBuilder sb = new StringBuilder();

        // split into lines
        String[] lines = javadoc.split("\n");

        boolean first = true;
        for (String line : lines) {
            line = line.trim();

            if (line.startsWith("**")) {
                continue;
            }
            // remove leading javadoc *
            if (line.startsWith("*")) {
                line = line.substring(1);
                line = line.trim();
            }

            // terminate if we reach @param, @return or @deprecated as we only want the javadoc summary
            if (line.startsWith("@param") || line.startsWith("@return") || line.startsWith("@deprecated")) {
                break;
            }

            // skip lines that are javadoc references
            if (line.startsWith("@")) {
                continue;
            }

            // remove all XML tags
            line = line.replaceAll("<.*?>", "");

            // remove all inlined javadoc links, eg such as {@link org.apache.camel.spi.Registry}
            // use #? to remove leading # in case its a local reference
            line = line.replaceAll("\\{\\@\\w+\\s#?([\\w.#(\\d,)]+)\\}", "$1");

            // we are starting from a new line, so add a whitespace
            if (!first) {
                sb.append(' ');
            }

            // create a new line
            StringBuilder cb = new StringBuilder();
            for (char c : line.toCharArray()) {
                if (Character.isJavaIdentifierPart(c) || VALID_CHARS.indexOf(c) != -1) {
                    cb.append(c);
                } else if (Character.isWhitespace(c)) {
                    // always use space as whitespace, also for line feeds etc
                    cb.append(' ');
                }
            }

            // append data
            String s = cb.toString().trim();
            sb.append(s);

            boolean empty = isNullOrEmpty(s);
            boolean endWithDot = s.endsWith(".");
            boolean haveText = sb.length() > 0;

            if (haveText && summary && (empty || endWithDot)) {
                // if we only want a summary, then skip at first empty line we encounter, or if the sentence ends with a dot
                break;
            }

            first = false;
        }

        String s = sb.toString();
        // remove double whitespaces, and trim
        s = s.replaceAll("\\s+", " ");
        // unescape http links
        s = s.replaceAll("\\\\(http:|https:)", "$1");
        return s.trim();
    }
}
