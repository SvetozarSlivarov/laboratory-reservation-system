package com.labreservation.catalog;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class CatalogDtos {

    private CatalogDtos() {}

    public record LaboratoryView(Long id, String name, String location, boolean enabled) {
        static LaboratoryView from(Laboratory laboratory) {
            return new LaboratoryView(laboratory.getId(), laboratory.getName(), laboratory.getLocation(),
                    laboratory.isEnabled());
        }
    }

    public record WorkstationView(Long id, Long laboratoryId, String code, String capabilities, boolean enabled) {
        public static WorkstationView from(Workstation workstation) {
            return new WorkstationView(workstation.getId(), workstation.getLaboratory().getId(),
                    workstation.getCode(), workstation.getCapabilities(), workstation.isEnabled());
        }
    }

    public record NewLaboratory(@NotBlank @Size(max = 120) String name,
                                @NotBlank @Size(max = 255) String location, @NotNull Boolean enabled) {}

    public record LaboratoryPatch(@Size(max = 120) @Pattern(regexp = "(?s).*\\S.*") String name,
                                  @Size(max = 255) @Pattern(regexp = "(?s).*\\S.*") String location, Boolean enabled) {}

    public record NewWorkstation(@NotBlank @Size(max = 64) String code,
                                 @Size(max = 500) String capabilities, @NotNull Boolean enabled) {}

    public record WorkstationPatch(@Size(max = 64) @Pattern(regexp = "(?s).*\\S.*") String code,
                                   @Size(max = 500) String capabilities, Boolean enabled) {}
}
