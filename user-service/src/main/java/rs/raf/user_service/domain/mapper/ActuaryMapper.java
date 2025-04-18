package rs.raf.user_service.domain.mapper;

import rs.raf.user_service.domain.dto.ActuaryDto;
import rs.raf.user_service.domain.entity.Employee;

import java.math.BigDecimal;

public class ActuaryMapper {
    public static ActuaryDto toActuaryDto(Employee employee) {
        if (employee == null) {
            return null;
        }

        ActuaryDto dto = new ActuaryDto();
        dto.setId(employee.getId());
        dto.setFirstName(employee.getFirstName());
        dto.setLastName(employee.getLastName());
        dto.setRole(employee.getRole().getName());
        dto.setProfit(BigDecimal.ZERO);
        return dto;
    }

}
