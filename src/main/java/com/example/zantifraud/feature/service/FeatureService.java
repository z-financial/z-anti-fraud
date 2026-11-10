package com.example.zantifraud.feature.service;

import com.example.zantifraud.event.model.EventModel;
import com.example.zantifraud.feature.model.FeatureSnapshot;

import java.util.Set;

public interface FeatureService {
    FeatureSnapshot queryFeatures(EventModel currentEvent, Set<String> requiredFeatures);
}
