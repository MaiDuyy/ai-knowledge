package com.security.security.domain;


import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Builder
@Getter
@Setter
public class Token {
    private String access;
    private String refresh;

}
