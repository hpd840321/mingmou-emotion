package com.school.emotion.listener;

import com.school.emotion.event.AlertTriggeredEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class AlertEventListener {

    private static final Logger log = LoggerFactory.getLogger(AlertEventListener.class);

    @EventListener
    public void onAlertTriggered(AlertTriggeredEvent event) {
        log.info("Alert: {}", event.getMessage());
    }
}
