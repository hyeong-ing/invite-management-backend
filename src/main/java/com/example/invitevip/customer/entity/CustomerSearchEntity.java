package com.example.invitevip.customer.entity;

import lombok.Getter;
import lombok.Setter;
import lombok.EqualsAndHashCode;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

@Document(indexName = "customers_search", createIndex = false)
@Getter
@Setter
@EqualsAndHashCode
public class CustomerSearchEntity {
    @Id
    private Long id;

    @Field(type = FieldType.Text, analyzer = "nori")
    private String name;

    @Field(type = FieldType.Keyword)
    private String grade;

    @Field(type = FieldType.Keyword)
    private String phone;

    @Field(type = FieldType.Keyword)
    private String code;

    @Field(type = FieldType.Text, analyzer = "nori")
    private String note;

    @Field(type = FieldType.Text)
    private String nameChosung;

    @Field(type = FieldType.Boolean)
    private boolean deleted;

    public static CustomerSearchEntity tombstone(Long id) {
        CustomerSearchEntity document = new CustomerSearchEntity();
        document.setId(id);
        document.setDeleted(true);
        return document;
    }
}
