package io.openems.edge.battery.audibatcon.statemachine;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.battery.audibatcon.enums.ContactorCommand;
import io.openems.edge.battery.audibatcon.enums.OperationModeBattery;
import io.openems.edge.battery.audibatcon.enums.RemainingBusSimulationStatus;
import io.openems.edge.battery.audibatcon.statemachine.StateMachine.State;
import io.openems.edge.battery.audibatcon.utils.Constants;
import io.openems.edge.common.statemachine.StateHandler;

public class GoStoppedHandler extends StateHandler<State, Context> {

	private Instant timeAtEntry = Instant.MIN;
	public final Logger log = LoggerFactory.getLogger(GoStoppedHandler.class);

	@Override
	protected void onEntry(Context context) throws OpenemsNamedException {
		var battery = context.getParent();
		this.timeAtEntry = Instant.now();
		battery._setMaxStopTime(false);
	}

	@Override
	public State runAndGetNextState(Context context) throws OpenemsNamedException {
		var battery = context.getParent();
		boolean isMaxAllowedStopTimePassed = Duration.between(this.timeAtEntry, Instant.now())
				.getSeconds() > Constants.MAX_ALLOWED_STOP_TIME;
		battery._setMaxStopTime(isMaxAllowedStopTimePassed);

		if (battery.hasFaults()) {
			// Has Faults -> error handling
			return State.ERROR;
		}

		if (battery.getRemainingBusSimulationStatus() == RemainingBusSimulationStatus.ACTIVE
				&& battery.getOperationMode() == OperationModeBattery.HV_ACTVE) {
			// Open Contactors!
			context.logInfo(this.log, "Open Contactors");
			battery.setContactorCommand(ContactorCommand.OPEN);
		} else if (battery.getRemainingBusSimulationStatus() == RemainingBusSimulationStatus.ACTIVE
				&& battery.getOperationMode() == OperationModeBattery.HV_INACTVE) {
			return State.STOPPED;
		} else if (battery.getRemainingBusSimulationStatus() == RemainingBusSimulationStatus.INACTIVE) {
			System.out.println("Check Remaining Bus Simulation config!");
		}

		return State.GO_STOPPED;
	}
}
