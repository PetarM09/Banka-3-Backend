package rs.raf.bank_service.domain.dto;

import lombok.*;
import rs.raf.bank_service.domain.enums.VerificationType;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateVerificationRequestDto {
    private Long userId;
    private Long targetId;
    private VerificationType verificationType;
    private String details;
}
