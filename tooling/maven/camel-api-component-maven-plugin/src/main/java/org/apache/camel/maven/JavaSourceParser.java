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
import org.jboss.forge.roaster.model.TypeVariable;
import org.jboss.forge.roaster.model.impl.AbstractGenericCapableJavaSource;
import org.jboss.forge.roaster.model.impl.AbstractJavaSource;
import org.jboss.forge.roaster.model.source.JavaInterfaceSource;
import org.jboss.forge.roaster.model.source.MethodHolderSource;
import org.jboss.forge.roaster.model.source.MethodSource;
import org.jboss.forge.roaster.model.source.ParameterSource;
import org.jboss.forge.roaster.model.source.TypeVariableSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.camel.tooling.util.JavadocHelper.sanitizeDescription;

/**
 * Parses source java to get Method Signatures from Method Summary.
 */
public class JavaSourceParser {

    private static final Logger LOG = LoggerFactory.getLogger(JavaSourceParser.class);

    private List<String> methods = new ArrayList<>();
    private Map<String, String> methodText = new HashMap<>();
    private Map<String, Map<String, String>> parameters = new LinkedHashMap<>();
    private Map<String, Map<String, String>> signaturesArguments = new LinkedHashMap<>();
    private String errorMessage;
    private String apiDescription;
    private final Map<String, String> methodDescriptions = new HashMap<>();

    private static String resolveParameterizedType(
            AbstractGenericCapableJavaSource clazz, MethodSource ms, ParameterSource ps, Type type) {
        String answer = resolveType(clazz, clazz, ms, type);

        if (type.isParameterized()) {
            // for parameterized types then it can get complex if they are variables (T, T extends Foo etc)
            // or if there are no bounds for these types which we then can't resolve.
            List<Type> types = type.getTypeArguments();
            boolean bounds = false;
            boolean found = false;
            for (Type t : types) {
                if (hasTypeVariableBounds(ms, clazz, t.getName())) {
                    bounds = true;
                    // okay now it gets complex as we have a type like T which is a type variable and we need to resolve that into
                    // what base class that is
                    String tn = resolveTypeVariable(ms, clazz, t.getName());
                    if (tn != null) {
                        answer = answer.replace(t.getName(), tn);
                        found = true;
                    }
                }
            }
            if (!bounds && !found) {
                // argh this is getting complex, it may be T or just java.lang.String but this **** generics and roaster
                // does not make this easy, so let see if we can find out if all the types are a qualified type or only a variable
                boolean fqn = types.stream().allMatch(t -> {
                    // if its from java itself then its okay
                    if (t.getQualifiedName().startsWith("java")) {
                        return true;
                    }
                    // okay lets assume its a type variable if the name is upper case only
                    boolean upperOnly = isUpperCaseOnly(t.getName());
                    return !upperOnly && t.getQualifiedName().indexOf('.') != -1;
                });
                if (!fqn) {
                    // remove generics we could not resolve that even if we have bounds information
                    bounds = true;
                    found = false;
                }
            }
            if (bounds && !found) {
                // remove generics we could not resolve that even if we have bounds information
                answer = type.getQualifiedName();
            }
        } else if (ms.hasTypeVariable(answer) || clazz.hasTypeVariable(answer)) {
            // okay now it gets complex as we have a type like T which is a type variable and we need to resolve that into
            // what base class that is
            answer = resolveTypeVariable(ms, clazz, answer);
        }
        if ((ps != null && ps.isVarArgs()) || type.isArray()) {
            // the old way with javadoc did not use varargs in the signature, so lets transform this to an array style
            answer = answer + "[]";
        }

        // remove java.lang. prefix as it should not be there
        answer = answer.replaceAll("java.lang.", "");
        return answer;
    }

    @SuppressWarnings("unchecked")
    public synchronized void parse(InputStream in, String innerClass) throws Exception {
        AbstractGenericCapableJavaSource rootClazz = (AbstractGenericCapableJavaSource) Roaster.parse(in);
        AbstractGenericCapableJavaSource clazz = rootClazz;

        if (innerClass != null) {
            // we want the inner class from the parent class
            clazz = findInnerClass(rootClazz, innerClass);
            if (clazz == null) {
                errorMessage = "Cannot find inner class " + innerClass + " in class: " + rootClazz.getQualifiedName();
                return;
            }
        }

        LOG.debug("Parsing class: {}", clazz.getQualifiedName());

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

        List<MethodSource> ml = ((MethodHolderSource) clazz).getMethods();
        for (MethodSource ms : ml) {
            String methodName = ms.getName();
            LOG.debug("Parsing method: {}", methodName);

            // should not be constructor and must not be private
            boolean isInterface = clazz instanceof JavaInterfaceSource;
            boolean accept = isInterface || (!ms.isConstructor() && ms.isPublic());
            if (!accept) {
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

            String result = resolveParameterizedType(clazz, ms, null, ms.getReturnType());
            if (result.isEmpty()) {
                result = "void";
            }
            LOG.trace("Parsed return type as: {}", result);

            List<JavaDocTag> params = ms.getJavaDoc().getTags("@param");

            Map<String, String> docs = new LinkedHashMap<>();
            Map<String, String> args = new LinkedHashMap<>();
            StringBuilder sb = new StringBuilder();
            sb.append("public ").append(result).append(" ").append(ms.getName()).append("(");
            List<ParameterSource> list = ms.getParameters();
            for (int i = 0; i < list.size(); i++) {
                ParameterSource ps = list.get(i);
                String name = ps.getName();
                String type = resolveType(rootClazz, clazz, ms, ps.getType());
                LOG.trace("Parsing parameter #{} ({} {})", i, type, name);

                // TODO: Call that other method
                if (ps.getType().isParameterized()) {
                    // for parameterized types then it can get complex if they are variables (T, T extends Foo etc)
                    // or if there are no bounds for these types which we then can't resolve.
                    List<Type> types = ps.getType().getTypeArguments();
                    boolean bounds = false;
                    boolean found = false;
                    for (Type t : types) {
                        if (hasTypeVariableBounds(ms, clazz, t.getName())) {
                            bounds = true;
                            // okay now it gets complex as we have a type like T which is a type variable and we need to resolve that into
                            // what base class that is
                            String tn = resolveTypeVariable(ms, clazz, t.getName());
                            if (tn != null) {
                                type = type.replace(t.getName(), tn);
                                found = true;
                            }
                        }
                    }
                    if (!bounds && !found) {
                        // argh this is getting complex, it may be T or just java.lang.String but this **** generics and roaster
                        // does not make this easy, so let see if we can find out if all the types are a qualified type or only a variable
                        boolean fqn = types.stream().allMatch(t -> {
                            // if its from java itself then its okay
                            if (t.getQualifiedName().startsWith("java")) {
                                return true;
                            }
                            // okay lets assume its a type variable if the name is upper case only
                            boolean upperOnly = isUpperCaseOnly(t.getName());
                            return !upperOnly && t.getQualifiedName().indexOf('.') != -1;
                        });
                        if (!fqn) {
                            // remove generics we could not resolve that even if we have bounds information
                            bounds = true;
                            found = false;
                        }
                    }
                    if (bounds && !found) {
                        // remove generics we could not resolve that even if we have bounds information
                        type = ps.getType().getQualifiedName();
                    }
                } else if (ms.hasTypeVariable(type) || clazz.hasTypeVariable(type)) {
                    // okay now it gets complex as we have a type like T which is a type variable and we need to resolve that into
                    // what base class that is
                    type = resolveTypeVariable(ms, clazz, type);
                }
                if (ps.isVarArgs() || ps.getType().isArray()) {
                    // the old way with javadoc did not use varargs in the signature, so lets transform this to an array style
                    type = type + "[]";
                }

                // remove all java.lang. prefixes
                type = type.replaceAll("java.lang.", "");

                sb.append(type);
                sb.append(" ").append(name);
                if (i < list.size() - 1) {
                    sb.append(", ");
                }

                // need documentation for this parameter
                docs.put(name, getJavadocValue(params, name));
                args.put(name, type);
            }
            sb.append(")");

            Map<String, String> existing = parameters.get(ms.getName());
            if (existing != null) {
                existing.putAll(docs);
            } else {
                parameters.put(ms.getName(), docs);
            }

            String signature = sb.toString();
            methods.add(signature);
            signaturesArguments.put(signature, args);
            methodText.put(ms.getName(), signature);
        }
    }

    private static boolean isUpperCaseOnly(String name) {
        for (int i = 0; i < name.length(); i++) {
            if (!Character.isUpperCase(name.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasTypeVariableBounds(MethodSource ms, AbstractGenericCapableJavaSource clazz, String type) {
        TypeVariable tv = ms.getTypeVariable(type);
        if (tv == null) {
            tv = clazz.getTypeVariable(type);
        }
        if (tv != null) {
            return !tv.getBounds().isEmpty();
        }
        return false;
    }

    private static String resolveTypeVariable(MethodSource ms, AbstractGenericCapableJavaSource clazz, String type) {
        TypeVariable tv = ms.getTypeVariable(type);
        if (tv == null) {
            tv = clazz.getTypeVariable(type);
        }
        if (tv != null) {
            List<Type> bounds = tv.getBounds();
            for (Type bt : bounds) {
                String bn = bt.getQualifiedName();
                if (!type.equals(bn)) {
                    return bn;
                }
            }
        }
        return null;
    }

    private static AbstractGenericCapableJavaSource findInnerClass(
            AbstractGenericCapableJavaSource rootClazz, String innerClass) {
        String[] parts = innerClass.split("\\$");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            AbstractGenericCapableJavaSource nested = (AbstractGenericCapableJavaSource) rootClazz.getNestedType(part);
            if (nested != null && i < parts.length - 1) {
                rootClazz = nested;
            } else {
                return nested;
            }
        }
        return null;
    }

    private static String resolveType(
            AbstractGenericCapableJavaSource rootClazz, AbstractGenericCapableJavaSource clazz, MethodSource ms, Type type) {
        String name = type.getName();
        // if the type is from a type variable (eg T extends Foo generic style)
        // then the type should be returned as-is
        TypeVariableSource tv = ms.getTypeVariable(name);
        if (tv == null) {
            tv = clazz.getTypeVariable(name);
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

    private static String resolveType(AbstractJavaSource rootClazz, AbstractJavaSource clazz, String type) {
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
    private static String getClassJavadocRaw(AbstractJavaSource clazz, String rawClass) {
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
        signaturesArguments.clear();
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

    public Map<String, Map<String, String>> getSignaturesArguments() {
        return signaturesArguments;
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
