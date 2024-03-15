package io.openems.edge.battery.audibatcon.statemachine;

import io.openems.edge.battery.audibatcon.statemachine.StateMachine.State;
import io.openems.edge.common.statemachine.StateHandler;

public class TargetUndefinedHandler extends StateHandler<State, Context> {

	@Override
	public State runAndGetNextState(Context context) {
		var battery = context.getParent();

		if (battery.hasFaults()) {
			// Has Faults -> error handling
			return State.ERROR;
		}

		switch (battery.getStartStopTarget()) {
		case UNDEFINED:
			// Stuck in UNDEFINED State
			return State.TARGET_UNDEFINED;

		case START:
			// force START
			return State.GO_RUNNING;

		case STOP:
			// force STOP
			return State.GO_STOPPED;
		}

		assert false;
		return State.TARGET_UNDEFINED; // can never happen
	}
}
