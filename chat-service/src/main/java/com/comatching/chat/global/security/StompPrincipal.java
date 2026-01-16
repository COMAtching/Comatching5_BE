package com.comatching.chat.global.security;

import java.security.Principal;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class StompPrincipal implements Principal {

	private String name;
}
