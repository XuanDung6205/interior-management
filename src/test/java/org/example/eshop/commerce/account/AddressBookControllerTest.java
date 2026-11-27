package org.example.eshop.commerce.account;

import org.example.eshop.commerce.common.CommerceExceptionHandler;
import org.example.eshop.commerce.port.CommerceActor;
import org.example.eshop.commerce.port.CurrentActorPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@ExtendWith(MockitoExtension.class)
class AddressBookControllerTest {
    @Mock
    CurrentActorPort currentActor;

    @Mock
    AddressBookService addresses;

    @InjectMocks
    AddressBookController controller;

    MockMvc mvc;
    LocalValidatorFactoryBean validator;

    @BeforeEach
    void setUp() {
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = standaloneSetup(controller)
                .setControllerAdvice(new CommerceExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void listUsesTheCurrentActor() throws Exception {
        stubCurrentActor();
        when(addresses.list(42L)).thenReturn(List.of(response()));

        mvc.perform(get("/api/account/addresses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(12))
                .andExpect(jsonPath("$[0].isDefault").value(true));

        verify(addresses).list(42L);
    }

    @Test
    void createUsesTheCurrentActorAndReturnsCreatedAddress() throws Exception {
        stubCurrentActor();
        when(addresses.create(eq(42L), any(AddressRequest.class))).thenReturn(response());

        mvc.perform(post("/api/account/addresses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(12));

        verify(addresses).create(eq(42L), any(AddressRequest.class));
    }

    @Test
    void invalidAddressIsRejectedBeforeServiceCall() throws Exception {
        mvc.perform(post("/api/account/addresses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recipientName":"","phone":"invalid","addressLine1":""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.recipientName").exists())
                .andExpect(jsonPath("$.fieldErrors.phone").exists())
                .andExpect(jsonPath("$.fieldErrors.addressLine1").exists());
    }

    @Test
    void updateAndDefaultRoutesUseCurrentActorAndPathAddressId() throws Exception {
        stubCurrentActor();
        when(addresses.update(eq(42L), eq(12L), any(AddressRequest.class))).thenReturn(response());
        when(addresses.makeDefault(42L, 12L)).thenReturn(response());

        mvc.perform(put("/api/account/addresses/12")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/account/addresses/12/default"))
                .andExpect(status().isOk());

        verify(addresses).update(eq(42L), eq(12L), any(AddressRequest.class));
        verify(addresses).makeDefault(42L, 12L);
    }

    @Test
    void deleteReturnsNoContentForTheCurrentActor() throws Exception {
        stubCurrentActor();
        mvc.perform(delete("/api/account/addresses/12"))
                .andExpect(status().isNoContent());

        verify(addresses).delete(42L, 12L);
    }

    private static String validRequest() {
        return """
                {
                  "label":"Home",
                  "recipientName":"Nguyen An",
                  "phone":"+84901234567",
                  "addressLine1":"1 Main Street",
                  "wardName":"Ward 1",
                  "districtName":"District 1",
                  "provinceName":"Ho Chi Minh City",
                  "countryCode":"VN"
                }
                """;
    }

    private static AddressResponse response() {
        return new AddressResponse(12L, "Home", "Nguyen An", "+84901234567", "1 Main Street",
                null, "Ward 1", "District 1", "Ho Chi Minh City", null, "VN", true);
    }

    private void stubCurrentActor() {
        when(currentActor.currentActor()).thenReturn(new CommerceActor(42L, null, Set.of("CUSTOMER")));
    }
}
