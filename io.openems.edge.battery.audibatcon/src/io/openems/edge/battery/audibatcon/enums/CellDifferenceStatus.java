package io.openems.edge.battery.audibatcon.enums;

import io.openems.common.types.OptionsEnum;

public enum CellDifferenceStatus implements OptionsEnum {
	UNDEFINED(-1, "Undefined"), //
	BALANCING_POSSIBLE(0, "Balancing possible"), //
	BALANCING_NOT_POSSIBLE(1, "Balancing not possible"), //
	BATTERY_FAILURE(2, "Battery Failure"); //

	private int value;
	private String name;

	private CellDifferenceStatus(int value, String name) {
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
