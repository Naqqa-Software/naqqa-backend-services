package com.naqqa.auth.dto.authorities;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubRoleResponseDto {
    private Long id;
    private String name;
    private Set<String> authorityNames;
}