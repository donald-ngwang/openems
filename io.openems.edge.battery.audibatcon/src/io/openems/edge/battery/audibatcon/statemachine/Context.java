package io.openems.edge.battery.audibatcon.statemachine;

import io.openems.edge.battery.audibatcon.AudiBatcon;
import io.openems.edge.battery.audibatcon.Config;
import io.openems.edge.common.statemachine.AbstractContext;

public class Context extends AbstractContext<AudiBatcon> {
	protected final AudiBatcon component;
	protected final Config config;

	public Context(AudiBatcon component, Config config) {
		super(component);
		this.component = component;
		this.config = config;
	}
}