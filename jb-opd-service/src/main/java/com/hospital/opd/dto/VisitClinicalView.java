package com.hospital.opd.dto;

import java.time.OffsetDateTime;

/** Clinical free text for one visit. Only returned to doctors allowed to read it (VisitAccessPolicy). */
public record VisitClinicalView(Long visitId, String status, String diagnosis, String consultationNotes,
                                OffsetDateTime completedAt) {}
