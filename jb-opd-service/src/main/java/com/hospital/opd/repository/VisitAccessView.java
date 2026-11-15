package com.hospital.opd.repository;

import com.hospital.opd.model.VisitStatus;

/** The two facts access decisions need, without loading the whole visit. */
public record VisitAccessView(String assignedDoctorUsername, VisitStatus status) {}
