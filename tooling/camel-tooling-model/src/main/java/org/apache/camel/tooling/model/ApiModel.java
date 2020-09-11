package org.apache.camel.tooling.model;

import java.util.ArrayList;
import java.util.List;

public final class ApiModel {

    private String name;
    private String description;
    private final List<ApiMethodModel> methods = new ArrayList<>();

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<ApiMethodModel> getMethods() {
        return methods;
    }

    public void addMethod(ApiMethodModel method) {
        this.methods.add(method);
    }
}
