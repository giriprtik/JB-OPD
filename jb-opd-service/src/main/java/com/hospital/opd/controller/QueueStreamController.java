package com.hospital.opd.controller;

import com.hospital.opd.stream.QueueStreamHub;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Live "something changed in your queue" notices for the worklist screens. */
@RestController
@RequestMapping("/api/v1/opd")
public class QueueStreamController {

    private final QueueStreamHub hub;

    public QueueStreamController(QueueStreamHub hub) {
        this.hub = hub;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAnyRole('NURSE', 'DOCTOR', 'ADMIN')")
    public SseEmitter stream(Authentication auth) {
        boolean doctor = auth.getAuthorities().stream().anyMatch(a -> "ROLE_DOCTOR".equals(a.getAuthority()));
        return hub.subscribe(auth.getName(), doctor);
    }
}
