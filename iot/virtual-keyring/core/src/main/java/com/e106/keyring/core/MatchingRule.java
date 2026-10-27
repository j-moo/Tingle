package com.e106.keyring.core;

/** Replace only this adapter when the AI team's matching input/rule contract is ready. */
public interface MatchingRule {
	boolean matches(DemoProfile local, DemoProfile remote);

	static MatchingRule alwaysMatch() {
		return (local, remote) -> true;
	}
}
