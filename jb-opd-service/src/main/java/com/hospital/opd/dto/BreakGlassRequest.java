package com.hospital.opd.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Why a non-assigned doctor needs this record now (shown in the visit history). */
public record BreakGlassRequest(@NotBlank @Size(min = 10, max = 500) String reason) {}
