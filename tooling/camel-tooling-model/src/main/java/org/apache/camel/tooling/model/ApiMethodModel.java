package org.apache.camel.tooling.model;

import java.util.ArrayList;
import java.util.List;

public final class ApiMethodModel {

    private String name;
    private String description;
    private final List<ComponentModel.ApiOptionModel> options = new ArrayList<>();

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

    public List<ComponentModel.ApiOptionModel> getOptions() {
        return options;
    }

    public void addApiOptionModel(ComponentModel.ApiOptionModel option) {
        options.add(option);
    }
}
