package io.openems.edge.battery.audibatcon.statemachine;

import io.openems.common.exceptions.InvalidValueException;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.battery.audibatcon.enums.OperationModeBattery;
import io.openems.edge.battery.audibatcon.enums.RemainingBusSimulationStatus;
import io.openems.edge.battery.audibatcon.statemachine.StateMachine.State;
import io.openems.edge.common.startstop.StartStop;
import io.openems.edge.common.statemachine.StateHandler;

public class RunningHandler extends StateHandler<State, Context> {

	@Override
	protected void onEntry(Context context) throws OpenemsNamedException {
		var battery = context.getParent();
		// Mark as started
		battery._setStartStop(StartStop.START);
		battery.getStartStopChannel().nextProcessImage();
	}

	@Override
	public State runAndGetNextState(Context context) throws InvalidValueException, IllegalArgumentException {
		var battery = context.getParent();
		if (battery.hasFaults()) {
			// Has Faults -> error handling
			return State.ERROR;
		}

		switch (battery.getStartStopTarget()) {
		case UNDEFINED:
		case START:
			break;
		case STOP:
			return State.GO_STOPPED;
		}

		if (battery.getRemainingBusSimulationStatus() == RemainingBusSimulationStatus.ACTIVE
				&& battery.getOperationMode() == OperationModeBattery.HV_ACTVE) {
			// Mark as started
			battery._setStartStop(StartStop.START);
			return State.RUNNING;
		}

		return State.ERROR;
	}
}
