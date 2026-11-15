package com.hospital.opd.event;

/**
 * Published whenever a visit's status changes. Carries IDs and status only, never patient details:
 * screens re-fetch their queue through the normal, authorised endpoints.
 */
public record VisitChanged(Long visitId, String toStatus) {}
