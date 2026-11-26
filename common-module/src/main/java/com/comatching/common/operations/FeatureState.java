package com.comatching.common.operations;

public record FeatureState(Feature feature, boolean open, String message, long version) {}
