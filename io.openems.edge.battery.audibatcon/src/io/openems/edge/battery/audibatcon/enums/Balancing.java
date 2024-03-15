package io.openems.edge.battery.audibatcon.enums;

import io.openems.common.types.OptionsEnum;

public enum Balancing implements OptionsEnum {
	UNDEFINED(-1, "Undefined"), //
	BALANCING_STOP(0, "Balancing-Stop"), //
	BALANCING_START(1, "Balancing-Start"); //

	private int value;
	private String name;

	private Balancing(int value, String name) {
		this.value = value;
		this.name = name;
	}

	@Override
	public int getValue() {
		return this.value;
	}

	@Override
	public String getName() {
		return this.name;
	}

	@Override
	public OptionsEnum getUndefined() {
		return UNDEFINED;
	}
}
