package com.comatching.user.infra.controller;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

@Component
public class MailRequestSource {
	private final List<IpAddressMatcher> trustedProxies;

	public MailRequestSource(@Value("${comatching.mail.trusted-proxies:}") String trustedCidrs) {
		this.trustedProxies = Arrays.stream(trustedCidrs.split(",")).map(String::trim)
			.filter(s -> !s.isEmpty()).map(IpAddressMatcher::new).toList();
	}

	public String of(HttpServletRequest request) {
		String peer = literal(request.getRemoteAddr());
		if (peer == null) return "unknown";
		if (!trusted(peer)) return peer;
		String chain = String.join(",", Collections.list(request.getHeaders("X-Forwarded-For")));
		if (chain.isBlank() || chain.length() > 2048) return peer;
		String[] hops = chain.split(",", -1);
		String current = peer;
		for (int i = hops.length - 1; i >= 0 && trusted(current); i--) {
			current = literal(hops[i].trim());
			if (current == null) return peer;
		}
		return current;
	}

	private boolean trusted(String ip) {
		return trustedProxies.stream().anyMatch(proxy -> proxy.matches(ip));
	}

	private static String literal(String value) {
		if (value == null || value.isBlank() || value.length() > 45) return null;
		try {
			if (value.contains(":") && value.matches("[0-9a-fA-F:.]+")) {
				return InetAddress.getByName(value).getHostAddress();
			}
			String[] octets = value.split("\\.", -1);
			if (octets.length != 4) return null;
			byte[] address = new byte[4];
			for (int i = 0; i < octets.length; i++) {
				if (!octets[i].matches("[0-9]{1,3}")) return null;
				int octet = Integer.parseInt(octets[i]);
				if (octet > 255) return null;
				address[i] = (byte)octet;
			}
			return InetAddress.getByAddress(address).getHostAddress();
		} catch (UnknownHostException e) {
			return null;
		}
	}
}
