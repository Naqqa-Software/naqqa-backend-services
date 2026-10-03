package com.naqqa.chatbot.entities;

import org.springframework.data.mongodb.core.mapping.Field;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ChatCard implements Serializable {

    public static final String GROUP_RESULTS = "results";
    public static final String GROUP_RELATED = "related";

    @Field("event_id")
    private String eventId;

    @Field("type")
    private String type;

    @Field("id")
    private Long id;

    @Field("slug")
    private String slug;

    @Field("title")
    private String title;

    @Field("image")
    private String image;

    @Field("price")
    private Double price;

    @Field("original_price")
    private Double originalPrice;

    @Field("discount")
    private Double discount;

    @Field("company_id")
    private Long companyId;

    @Field("company")
    private String company;

    @Field("company_logo")
    private String companyLogo;

    @Field("valid_to")
    private String validTo;

    @Field("path")
    private String path;

    @Field("sponsored")
    private boolean sponsored;

    @Field("group")
    private String group;
}
