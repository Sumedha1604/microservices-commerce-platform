package com.sumedha.commerce.user.integration;

import com.sumedha.commerce.user.dto.request.*;
import com.sumedha.commerce.user.entity.UserProfile;
import com.sumedha.commerce.user.enums.AddressType;
import com.sumedha.commerce.user.repository.*;
import com.sumedha.commerce.user.service.UserProfileService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class UserPostgresIntegrationTest {
 @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("user_test").withUsername("user").withPassword("user");
 @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);}
 @Autowired UserProfileService service; @Autowired UserProfileRepository profiles; @Autowired AddressRepository addresses; @Autowired UserPreferenceRepository preferences; @Autowired JdbcTemplate jdbc;
 @BeforeEach void clear(){addresses.deleteAll();preferences.deleteAll();profiles.deleteAll();}
 @Test void flywayCreatesAllTables(){var names=jdbc.queryForList("select table_name from information_schema.tables where table_schema='public'",String.class);assertTrue(names.containsAll(List.of("user_profiles","addresses","user_preferences")));}
 @Test void repositoriesPersistConstraintsAndStringEnums(){UUID id=UUID.randomUUID();var profile=service.create(id,new CreateProfileRequest("A","B",null,null,null));assertTrue(profiles.existsByAuthUserId(id));assertThrows(DataIntegrityViolationException.class,()->profiles.saveAndFlush(new UserProfile(id,"C","D",null,null,null)));var address=service.createAddress(id,new CreateAddressRequest(AddressType.SHIPPING,"A","One",null,"Stockholm",null,"11111","se",null,true));assertEquals("SHIPPING",jdbc.queryForObject("select address_type from addresses where address_id=?",String.class,address.addressId()));assertEquals("SE",address.countryCode());assertTrue(preferences.findByProfileId(profile.profileId()).isPresent());}
 @Test void databaseBackedProfileAddressAndPreferencesFlows(){UUID id=UUID.randomUUID();var created=service.create(id,new CreateProfileRequest(" Ada "," Lovelace ","+46",null,LocalDate.of(1815,12,10)));assertEquals("Ada",service.get(id).firstName());var updated=service.update(id,new UpdateProfileRequest("Grace","Hopper","2","avatar",LocalDate.of(1906,12,9)));assertEquals("Grace",updated.firstName());var a=service.createAddress(id,new CreateAddressRequest(AddressType.SHIPPING,"Grace","One",null,"Stockholm",null,"1","se",null,true));var b=service.createAddress(id,new CreateAddressRequest(AddressType.BILLING,"Grace","Two",null,"Gothenburg",null,"2","us",null,true));assertEquals(2,service.addresses(id).size());assertFalse(service.address(id,a.addressId()).isDefault());assertTrue(service.address(id,b.addressId()).isDefault());service.updateAddress(id,a.addressId(),new UpdateAddressRequest(AddressType.BOTH,"Grace","Three",null,"Malmo",null,"3","dk",null,true));assertTrue(service.address(id,a.addressId()).isDefault());assertFalse(service.address(id,b.addressId()).isDefault());service.deleteAddress(id,b.addressId());assertEquals(1,service.addresses(id).size());assertEquals("USD",service.preferences(id).currency());var pref=service.updatePreferences(id,new UpdatePreferencesRequest("sv","sek",true,false));assertEquals("SEK",pref.currency());assertTrue(service.preferences(id).marketingEmails());assertFalse(service.preferences(id).orderNotifications());}
}
