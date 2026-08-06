//package com.security.security.service;
//
//
//import com.security.security.entity.Credential;
//import com.security.security.entity.Role;
//import com.security.security.entity.User;
//import com.security.security.entity.enumeration.Authority;
//import com.security.security.repository.CredentialRepository;
//import com.security.security.repository.UserRepository;
//import com.security.security.service.impl.UserServiceImpl;
//import org.junit.jupiter.api.DisplayName;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.extension.ExtendWith;
//import org.mockito.InjectMocks;
//import org.mockito.Mock;
//import org.mockito.Mockito;
//import org.mockito.junit.jupiter.MockitoExtension;
//
//import java.time.LocalDateTime;
//import java.util.Optional;
//
//import static org.assertj.core.api.Assertions.assertThat;
//import static org.mockito.ArgumentMatchers.anyString;
//import static org.mockito.Mockito.verify;
//import static org.mockito.Mockito.when;
////import static org.mockito.Mockito.when;
//
//
//@ExtendWith(MockitoExtension.class)
//public class UserServiceTest {
//    @Mock
//    private UserRepository userRepository;
//
//    @Mock
//    private CredentialRepository credentialRepository;
//
//    @InjectMocks
//    private UserServiceImpl userServiceImpl;
//
//    @Test
//    @DisplayName("Test Find user By ID")
//    public void getUserByIdTest(){
//        //Arrange - Given
//        var user = new User();
//        user.setFullName("Pham Mai Duy");
//        user.setId(1L);
////
//        var role = new Role("USER" , Authority.USER);
//        user.setRole(role);
//
//        user.setCreateAt(LocalDateTime.of(1111,11 ,1 ,1 ,1 ,1 ));
//        user.setUpdateAt(LocalDateTime.of(1111,11 ,1 ,1 ,1 ,1 ));
//        user.setLastLogin(LocalDateTime.of(1111,11 ,1 ,1 ,1 ,1 ));
////
//        var credential = new Credential();
//        credential.setUpdateAt(LocalDateTime.of(1111,11 ,1 ,1 ,1 ,1 ));
//        credential.setPassword("password");
//        credential.setUser(user);
////
////        Mockito.when(userRepository.findUserByUserId("1")).thenReturn(Optional.of(user));
////        Mockito.when(credentialRepository.getCredentialByUserId(1L)).thenReturn(Optional.of(credential));
//        when(userRepository.findUserByUserId(anyString())).thenReturn(Optional.of(user));
//        Mockito.when(credentialRepository.getCredentialByUserId(1L)).thenReturn(Optional.of(credential));
//        //Act - when
//       var userByUserId =  userServiceImpl.getUserByUserId("1");
//        verify(userRepository).findUserByUserId("1");
//       //Assert - Then
//        assertThat(userByUserId.getFullName()).isEqualTo("Pham Mai Duy");
//
//
//    }
//}
