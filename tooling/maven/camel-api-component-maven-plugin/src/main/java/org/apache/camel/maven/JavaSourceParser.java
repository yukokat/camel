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
package org.apache.camel.maven;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import org.apache.camel.util.StringHelper;
import org.jboss.forge.roaster.Roaster;
import org.jboss.forge.roaster._shade.org.eclipse.jdt.core.dom.ASTNode;
import org.jboss.forge.roaster.model.JavaDocTag;
import org.jboss.forge.roaster.model.Type;
import org.jboss.forge.roaster.model.source.JavaClassSource;
import org.jboss.forge.roaster.model.source.MethodSource;
import org.jboss.forge.roaster.model.source.ParameterSource;
import org.jboss.forge.roaster.model.source.TypeVariableSource;

import static org.apache.camel.tooling.util.JavadocHelper.sanitizeDescription;

/**
 * Parses source java to get Method Signatures from Method Summary.
 */
public class JavaSourceParser {

    private List<String> methods = new ArrayList<>();
    private Map<String, String> methodText = new HashMap<>();
    private Map<String, Map<String, String>> parameters = new LinkedHashMap<>();
    private String errorMessage;
    private String apiDescription;
    private final Map<String, String> methodDescriptions = new HashMap<>();

    public synchronized void parse(InputStream in, String innerClass) throws Exception {
        JavaClassSource rootClazz = (JavaClassSource) Roaster.parse(in);
        JavaClassSource clazz = rootClazz;

        if (innerClass != null) {
            // we want the inner class from the parent class
            clazz = findInnerClass(rootClazz, innerClass);
            if (clazz == null) {
                errorMessage = "Cannot find inner class " + innerClass + " in class: " + rootClazz.getQualifiedName();
                return;
            }
        }

        String rawClass = clazz.toUnformattedString();
        String doc = getClassJavadocRaw(clazz, rawClass);
        apiDescription = sanitizeJavaDocValue(doc, true);
        if (apiDescription == null || apiDescription.isEmpty()) {
            rawClass = rootClazz.toUnformattedString();
            doc = getClassJavadocRaw(rootClazz, rawClass);
            apiDescription = sanitizeJavaDocValue(doc, true);
        }
        if (apiDescription != null && apiDescription.indexOf('.') > 0) {
            apiDescription = StringHelper.before(apiDescription, ".");
        }

        for (MethodSource ms : clazz.getMethods()) {
            // should not be constructor and must be public
            if (!ms.isPublic() || ms.isConstructor()) {
                continue;
            }

            doc = getMethodJavadocRaw(ms, rawClass);
            doc = sanitizeJavaDocValue(doc, true);
            if (doc != null && doc.indexOf('.') > 0) {
                doc = StringHelper.before(doc, ".");
            }
            if (doc != null && !doc.isEmpty()) {
                methodDescriptions.put(ms.getName(), doc);
            }

            String signature = ms.toSignature();
            // roaster signatures has return values at end
            // public create(String, AddressRequest) : Result

            int pos = signature.indexOf(':');
            if (pos != -1) {
                String result = signature.substring(pos + 1).trim();
                // lets use FQN types
                if (!"void".equals(result)) {
                    result = resolveType(rootClazz, clazz, result);
                }
                if (result.isEmpty()) {
                    result = "void";
                }

                List<JavaDocTag> params = ms.getJavaDoc().getTags("@param");

                Map<String, String> docs = new LinkedHashMap<>();
                StringBuilder sb = new StringBuilder();
                sb.append("public ").append(result).append(" ").append(ms.getName()).append("(");
                List<ParameterSource> list = ms.getParameters();
                for (int i = 0; i < list.size(); i++) {
                    ParameterSource ps = list.get(i);
                    String name = ps.getName();
                    String type = resolveType(rootClazz, clazz, ms, ps.getType());
                    if (type.startsWith("java.lang.")) {
                        type = type.substring(10);
                    }
                    sb.append(type);
                    if (ps.isVarArgs() || ps.getType().isArray()) {
                        // the old way with javadoc did not use varargs in the signature, so lets transform this to an array style
                        sb.append("[]");
                    }
                    sb.append(" ").append(name);
                    if (i < list.size() - 1) {
                        sb.append(", ");
                    }

                    // need documentation for this parameter
                    docs.put(name, getJavadocValue(params, name));
                }
                sb.append(")");

                signature = sb.toString();
                Map<String, String> existing = parameters.get(ms.getName());
                if (existing != null) {
                    existing.putAll(docs);
                } else {
                    parameters.put(ms.getName(), docs);
                }
            }

            methods.add(signature);
            methodText.put(ms.getName(), signature);
        }
    }

    private static JavaClassSource findInnerClass(JavaClassSource rootClazz, String innerClass) {
        String[] parts = innerClass.split("\\$");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            JavaClassSource nested = (JavaClassSource) rootClazz.getNestedType(part);
            if (nested != null && i < parts.length - 1) {
                rootClazz = nested;
            } else {
                return nested;
            }
        }
        return null;
    }

    private static String resolveType(JavaClassSource rootClazz, JavaClassSource clazz, MethodSource ms, Type type) {
        String name = type.getName();
        // if the type is from a type variable (eg T extends Foo generic style)
        // then the type should be returned as-is
        TypeVariableSource tv = ms.getTypeVariable(name);
        if (tv == null) {
            clazz.getTypeVariable(name);
        }
        if (tv != null) {
            return type.getName();
        }

        String answer = resolveType(rootClazz, clazz, name);
        List<Type> types = type.getTypeArguments();
        if (!types.isEmpty()) {
            if (type.isArray()) {
                answer = type.getQualifiedNameWithGenerics();
            } else {
                StringJoiner sj = new StringJoiner(", ");
                for (Type arg : types) {
                    sj.add(resolveType(rootClazz, clazz, ms, arg));
                }
                answer = answer + "<" + sj.toString() + ">";
            }
        }
        return answer;
    }

    private static String resolveType(JavaClassSource rootClazz, JavaClassSource clazz, String type) {
        if ("void".equals(type)) {
            return "void";
        }

        // workaround bug in Roaster about resolving type that was an inner class
        // is this an inner class
        boolean inner = rootClazz.getNestedType(type) != null;
        if (inner) {
            return rootClazz.getQualifiedName() + "$" + type;
        }
        inner = clazz.getNestedType(type) != null;
        if (inner) {
            return clazz.getQualifiedName() + "$" + type;
        }
        int dot = type.indexOf('.');
        if (Character.isUpperCase(type.charAt(0)) && dot != -1) {
            // okay its likely a inner class with a nested sub type, so resolving is even more complex
            String parent = type.substring(0, dot);
            String child = type.substring(dot + 1);
            inner = rootClazz.getNestedType(parent) != null;
            if (inner) {
                return rootClazz.getQualifiedName() + "$" + type.replace('.', '$');
            }
            inner = clazz.getNestedType(type) != null;
            if (inner) {
                return clazz.getQualifiedName() + "$" + type.replace('.', '$');
            }
            if (parent.equals(rootClazz.getName())) {
                inner = rootClazz.getNestedType(child) != null;
                if (inner) {
                    return rootClazz.getQualifiedName() + "$" + child.replace('.', '$');
                }
                inner = clazz.getNestedType(child) != null;
                if (inner) {
                    return clazz.getQualifiedName() + "$" + child.replace('.', '$');
                }
            }
            String resolvedType = rootClazz.resolveType(parent);
            return resolvedType + "$" + child;
        }

        // okay attempt to resolve the type
        String resolvedType = clazz.resolveType(type);
        if (resolvedType.equals(type)) {
            resolvedType = rootClazz.resolveType(type);
        }
        return resolvedType;
    }

    private static String getJavadocValue(List<JavaDocTag> params, String name) {
        for (JavaDocTag tag : params) {
            String key = tag.getValue();
            if (key.startsWith(name)) {
                String desc = key.substring(name.length());
                desc = sanitizeJavaDocValue(desc, false);
                return desc;
            }
        }
        return "";
    }

    /**
     * Gets the class javadoc raw (incl line breaks and tags etc). The roaster API returns the javadoc with line breaks
     * and others removed
     */
    private static String getClassJavadocRaw(JavaClassSource clazz, String rawClass) {
        Object obj = clazz.getJavaDoc().getInternal();
        ASTNode node = (ASTNode) obj;
        int pos = node.getStartPosition();
        int len = node.getLength();
        if (pos > 0 && len > 0) {
            return rawClass.substring(pos, pos + len);
        } else {
            return null;
        }
    }

    /**
     * Gets the method javadoc raw (incl line breaks and tags etc). The roaster API returns the javadoc with line breaks
     * and others removed
     */
    private static String getMethodJavadocRaw(MethodSource ms, String rawClass) {
        Object obj = ms.getJavaDoc().getInternal();
        ASTNode node = (ASTNode) obj;
        int pos = node.getStartPosition();
        int len = node.getLength();
        if (pos > 0 && len > 0) {
            return rawClass.substring(pos, pos + len);
        } else {
            return null;
        }
    }

    private static String sanitizeJavaDocValue(String desc, boolean summary) {
        if (desc == null) {
            return null;
        }

        // remove leading/trailing garbage
        desc = desc.trim();
        while (desc.startsWith("\n") || desc.startsWith("}") || desc.startsWith("-") || desc.startsWith("/")) {
            desc = desc.substring(1);
            desc = desc.trim();
        }
        while (desc.endsWith("-") || desc.endsWith("/")) {
            desc = desc.substring(0, desc.length() - 1);
            desc = desc.trim();
        }
        desc = sanitizeDescription(desc, summary);
        if (desc != null && !desc.isEmpty()) {
            // upper case first letter
            char ch = desc.charAt(0);
            if (Character.isAlphabetic(ch) && !Character.isUpperCase(ch)) {
                desc = Character.toUpperCase(ch) + desc.substring(1);
            }
            // remove ending dot if there is the text is just alpha or whitespace
            boolean removeDot = true;
            char[] arr = desc.toCharArray();
            for (int i = 0; i < arr.length; i++) {
                ch = arr[i];
                boolean accept = Character.isAlphabetic(ch) || Character.isWhitespace(ch) || ch == '\''
                        || ch == '-' || ch == '_';
                boolean last = i == arr.length - 1;
                accept |= last && ch == '.';
                if (!accept) {
                    removeDot = false;
                    break;
                }
            }
            if (removeDot && desc.endsWith(".")) {
                desc = desc.substring(0, desc.length() - 1);
            }
            desc = desc.trim();
        }
        return desc;
    }

    public void reset() {
        methods.clear();
        methodText.clear();
        parameters.clear();
        methodDescriptions.clear();
        errorMessage = null;
        apiDescription = null;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public List<String> getMethods() {
        return methods;
    }

    public Map<String, String> getMethodText() {
        return methodText;
    }

    public Map<String, Map<String, String>> getParameters() {
        return parameters;
    }

    public String getApiDescription() {
        return apiDescription;
    }

    public Map<String, String> getMethodDescriptions() {
        return methodDescriptions;
    }
}
