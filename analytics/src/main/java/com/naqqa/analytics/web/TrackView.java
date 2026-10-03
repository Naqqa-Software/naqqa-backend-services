package com.naqqa.analytics.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface TrackView {

    String entityType();

    String entityIdParam() default "id";

    String event() default "item_view";

    String pageType() default "";
}
