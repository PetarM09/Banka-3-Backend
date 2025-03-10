package rs.raf.user_service.unit;


import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import rs.raf.user_service.domain.dto.PermissionDto;
import rs.raf.user_service.domain.dto.PermissionRequestDto;
import rs.raf.user_service.domain.entity.Employee;
import rs.raf.user_service.domain.entity.Permission;
import rs.raf.user_service.repository.AuthTokenRepository;
import rs.raf.user_service.repository.PermissionRepository;
import rs.raf.user_service.repository.UserRepository;
import rs.raf.user_service.service.UserService;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PermissionRepository permissionRepository;
    @Mock
    private AuthTokenRepository authTokenRepository;
    @InjectMocks
    private UserService userService;
    @Mock
    private RabbitTemplate rabbitTemplate;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }


    @Test
    void getUserPermissions_UserExists_ReturnsPermissions() {

        Long userId = 1L;
        Employee user = new Employee();
        Permission permission = new Permission();
        permission.setId(1L);
        permission.setName("READ");
        user.setPermissions(new HashSet<>(Set.of(permission)));

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));


        List<PermissionDto> permissions = userService.getUserPermissions(userId);


        assertNotNull(permissions);
        assertEquals(1, permissions.size());
        assertEquals("READ", permissions.get(0).getName());
        verify(userRepository, times(1)).findById(userId);
    }

    @Test
    void getUserPermissions_UserNotFound_ThrowsException() {

        Long userId = 1L;
        when(userRepository.findById(userId)).thenReturn(Optional.empty());


        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            userService.getUserPermissions(userId);
        });

        assertEquals("User not found", exception.getMessage());
        verify(userRepository, times(1)).findById(userId);
    }

    @Test
    void addPermissionToUser_UserAndPermissionExist_AddsPermission() {

        Long userId = 1L;
        Long permissionId = 2L;
        Employee user = new Employee();
        Permission permission = new Permission();
        permission.setId(permissionId);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(permissionRepository.findById(permissionId)).thenReturn(Optional.of(permission));


        userService.addPermissionToUser(userId, new PermissionRequestDto(permission.getId()));


        assertTrue(user.getPermissions().contains(permission));
        verify(userRepository, times(1)).save(user);
    }

    @Test
    void addPermissionToUser_UserAlreadyHasPermission_ThrowsException() {

        Long userId = 1L;
        Long permissionId = 2L;
        Employee user = new Employee();
        Permission permission = new Permission();
        permission.setId(permissionId);
        user.setPermissions(new HashSet<>(Set.of(permission)));

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(permissionRepository.findById(permissionId)).thenReturn(Optional.of(permission));


        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            userService.addPermissionToUser(userId, new PermissionRequestDto(permission.getId()));
        });

        assertEquals("User already has this permission", exception.getMessage());
        verify(userRepository, never()).save(user);
    }

    @Test
    void removePermissionFromUser_UserAndPermissionExist_RemovesPermission() {

        Long userId = 1L;
        Long permissionId = 2L;
        Employee user = new Employee();
        Permission permission = new Permission();
        permission.setId(permissionId);
        user.setPermissions(new HashSet<>(Set.of(permission)));

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(permissionRepository.findById(permissionId)).thenReturn(Optional.of(permission));


        userService.removePermissionFromUser(userId, permissionId);


        assertFalse(user.getPermissions().contains(permission));
        verify(userRepository, times(1)).save(user);
    }

    @Test
    void removePermissionFromUser_UserDoesNotHavePermission_ThrowsException() {

        Long userId = 1L;
        Long permissionId = 2L;
        Employee user = new Employee();
        Permission permission = new Permission();
        permission.setId(permissionId);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(permissionRepository.findById(permissionId)).thenReturn(Optional.of(permission));


        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            userService.removePermissionFromUser(userId, permissionId);
        });

        assertEquals("User does not have this permission", exception.getMessage());
        verify(userRepository, never()).save(user);
    }

    @Test
    void addPermissionToUser_PermissionNotFound_ThrowsException() {

        Long userId = 1L;
        Long permissionId = 2L;
        Employee user = new Employee();

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(permissionRepository.findById(permissionId)).thenReturn(Optional.empty());


        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            userService.addPermissionToUser(userId, new PermissionRequestDto(permissionId));
        });

        assertEquals("Permission not found", exception.getMessage());
        verify(userRepository, never()).save(user);
    }
//    @Test
//    public void testCreateClient_Success() {
//        UserDto userDTO = new UserDto();
//        userDTO.setEmail("test@example.com");
//        userDTO.setFirstName("John");
//        userDTO.setLastName("Doe");
//
//        userService.createUser(userDTO);
//
//        verify(userRepository, times(1)).save(any(Client.class));
//
//        verify(authTokenRepository, times(1)).save(any(AuthToken.class));
//
//        verify(rabbitTemplate, times(1)).convertAndSend(eq("activate-client-account"), any(EmailRequestDto.class));
//    }


}


