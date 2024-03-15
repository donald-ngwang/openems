package io.openems.edge.battery.audibatcon.statemachine;

import java.time.Duration;
import java.time.Instant;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.battery.audibatcon.enums.ContactorCommand;
import io.openems.edge.battery.audibatcon.enums.OperationModeBattery;
import io.openems.edge.battery.audibatcon.enums.RemainingBusSimulationStatus;
import io.openems.edge.battery.audibatcon.statemachine.StateMachine.State;
import io.openems.edge.battery.audibatcon.utils.Constants;
import io.openems.edge.common.statemachine.StateHandler;

public class GoRunningHandler extends StateHandler<State, Context> {

	private Instant timeAtEntry = Instant.MIN;

	@Override
	protected void onEntry(Context context) throws OpenemsNamedException {
		var battery = context.getParent();
		this.timeAtEntry = Instant.now();
		battery._setMaxStartTime(false);

	}

	@Override
	public State runAndGetNextState(Context context) throws OpenemsNamedException {
		var battery = context.getParent();
		boolean isMaxAllowedStartTimePassed = Duration.between(this.timeAtEntry, Instant.now())
				.getSeconds() > Constants.MAX_ALLOWED_START_TIME;
		battery._setMaxStartTime(isMaxAllowedStartTimePassed);

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
				&& battery.getOperationMode() == OperationModeBattery.HV_INACTVE) {
			// Close Contactors!
			System.out.println("Close Contactors!!");
			battery.setContactorCommand(ContactorCommand.CLOSE);
		} else if (battery.getRemainingBusSimulationStatus() == RemainingBusSimulationStatus.ACTIVE
				&& battery.getOperationMode() == OperationModeBattery.HV_ACTVE) {
			return State.RUNNING;
		} else if (battery.getRemainingBusSimulationStatus() == RemainingBusSimulationStatus.INACTIVE) {
			System.out.println("Check Remaining Bus Simulation config!");
		}
		return State.GO_RUNNING;
	}
}
