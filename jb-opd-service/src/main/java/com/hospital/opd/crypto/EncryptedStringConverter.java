package com.hospital.opd.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

/**
 * Encrypts a String entity field on write and decrypts it on read. Spring Boot wires Hibernate's bean
 * container to Spring, so this converter gets the FieldCipher injected.
 * Native queries bypass converters: decrypt their columns explicitly (see VisitService#toItem).
 */
@Component
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private final FieldCipher cipher;

    public EncryptedStringConverter(FieldCipher cipher) {
        this.cipher = cipher;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return cipher.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return cipher.decrypt(dbData);
    }
}
