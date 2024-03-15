package io.openems.edge.battery.audibatcon;

import java.util.concurrent.atomic.AtomicReference;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.channel.AccessMode;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.exceptions.OpenemsException;
import io.openems.edge.battery.api.Battery;
import io.openems.edge.battery.audibatcon.enums.RemainingBusSimulationCommand;
import io.openems.edge.battery.audibatcon.enums.RemainingBusSimulationStatus;
import io.openems.edge.battery.audibatcon.statemachine.Context;
import io.openems.edge.battery.audibatcon.statemachine.StateMachine;
import io.openems.edge.battery.audibatcon.statemachine.StateMachine.State;
import io.openems.edge.battery.audibatcon.utils.Constants;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ElementToChannelConverter;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.BitsWordElement;
import io.openems.edge.bridge.modbus.api.element.DummyRegisterElement;
import io.openems.edge.bridge.modbus.api.element.StringWordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedQuadruplewordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.task.FC16WriteRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.common.modbusslave.ModbusSlave;
import io.openems.edge.common.modbusslave.ModbusSlaveTable;
import io.openems.edge.common.startstop.StartStop;
import io.openems.edge.common.startstop.StartStoppable;
import io.openems.edge.common.taskmanager.Priority;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Battery.AudiBatcon", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE, //
		property = { //
				EventConstants.EVENT_TOPIC + "=" + EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE //
		})
public class AudiBatconImpl extends AbstractOpenemsModbusComponent
		implements AudiBatcon, Battery, OpenemsComponent, EventHandler, ModbusSlave, StartStoppable {

	public final Logger log = LoggerFactory.getLogger(AudiBatconImpl.class);
	private String modbusBridgeId;

	@Reference
	protected ConfigurationAdmin cm;

	@Reference
	protected ComponentManager componentManager;

	/**
	 * Manages the {@link State}s of the StateMachine.
	 */

	private final StateMachine stateMachine = new StateMachine(State.TARGET_UNDEFINED);

	private Config config;

	public AudiBatconImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				Battery.ChannelId.values(), //
				AudiBatcon.ChannelId.values(), //
				StartStoppable.ChannelId.values() //
		);
	}

	@Reference(policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.MANDATORY)
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	@Activate
	void activate(ComponentContext context, Config config) throws OpenemsNamedException {
		this.config = config;
		if (super.activate(context, config.id(), config.alias(), config.enabled(), config.modbusUnitId(), this.cm,
				"Modbus", config.modbus_id())) {
			return;
		}
	}

	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	public void handleEvent(Event event) {
		if (!this.isEnabled()) {
			return;
		}
		switch (event.getTopic()) {
		case EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE:
			this.handleRemainingBusSimulation();
			this.handleStateMachine();
			break;
		}
	}

	/**
	 * Handles the State-Machine.
	 */
	private void handleStateMachine() {

		// Store the current State
		this.channel(AudiBatcon.ChannelId.STATE_MACHINE).setNextValue(this.stateMachine.getCurrentState());

		// Initialize 'Start-Stop' Channel
		this._setStartStop(StartStop.UNDEFINED);

		// Prepare Context
		try {

			Context context = new Context(this, this.config);

			// Call the StateMachine
			this.stateMachine.run(context);

			this.channel(AudiBatcon.ChannelId.RUN_FAILED).setNextValue(false);

		} catch (OpenemsNamedException e) {
			this.channel(AudiBatcon.ChannelId.RUN_FAILED).setNextValue(true);
			this.logError(this.log, "StateMachine failed: " + e.getMessage());
		}
	}

	public String getModbusBridgeId() {
		return this.modbusBridgeId;
	}

	private static final int BATTERY_SIGNALS_START_ADDRESS = 4096;
	private static final int BATTERY_SIGNALS_GROUP = 0;
	private static final int METADATA_GROUP = 1;
	private static final int STATUS_INFO_GROUP = 9;
	private static final int CAN_VALUES_GROUP = 10;
	private static final int OPERATION_MODES_2NDL_GROUP = 12;
	private static final int FAILURE_SIGNALS_GROUP = 13;
	private static final int BALANCING_GROUP = 14;
	private static final int CAN_VALUES_HIGH_RESOLUTION_GROUP = 15;
	private static final int ISOLATION_RESISTANCE_GROUP = 2;
	private static final int TEMPERATURES_GROUP = 3;
	private static final int STATE_OF_CHARGE_GROUP = 4;
	private static final int VOLTAGE_GROUP = 5;
	private static final int VOLTAGE_CELL_GROUP = 6;
	private static final int PACK_CURRENT_GROUP = 7;
	private static final int ISOLATION_RESISTANCE_STATUS_GROUP = 8;

	@Override
	protected ModbusProtocol defineModbusProtocol() throws OpenemsException {
		int batconChannel = this.config.modbusUnitId();

		return new ModbusProtocol(this, //

				/*
				 * BATCON-Commands
				 */
				new FC16WriteRegistersTask(1, //
						m(AudiBatcon.ChannelId.BATCON_RESET, new UnsignedWordElement(1)), //
						new DummyRegisterElement(2, 3),
						m(AudiBatcon.ChannelId.SET_BATTERY_TYPE, new UnsignedWordElement(4)), //
						new DummyRegisterElement(5, 16),
						m(AudiBatcon.ChannelId.WRITE_IP_ADDRESS, new UnsignedQuadruplewordElement(17)), //
						m(AudiBatcon.ChannelId.WRITE_SUBNET_MASK, new UnsignedQuadruplewordElement(21)), //
						m(AudiBatcon.ChannelId.RESET_USER_NETWORK_ADDRESS, new UnsignedWordElement(25)) //
				),

				/*
				 * BATCON-Data
				 */
				new FC3ReadRegistersTask(256, Priority.LOW, //
						m(AudiBatcon.ChannelId.SERIAL_NUMBER, new StringWordElement(256, 8)), //
						m(AudiBatcon.ChannelId.HARDWARE_VERSION, new StringWordElement(264, 2)), //
						m(AudiBatcon.ChannelId.SOFTWARE_VERSION, new StringWordElement(266, 3)), //
						m(AudiBatcon.ChannelId.BOOTLOADER_SOFTWARE_VERSION, new StringWordElement(269, 2)) //
				),

				/**
				 * Battery Signals
				 */

				/*
				 * Battery Commands
				 */
				new FC16WriteRegistersTask(
						(batconChannel * BATTERY_SIGNALS_START_ADDRESS + BATTERY_SIGNALS_GROUP * 256 + 0), //
						m(AudiBatcon.ChannelId.REMAINING_BUS_SIMULATION_COMMAND,
								new UnsignedWordElement(batconChannel * BATTERY_SIGNALS_START_ADDRESS
										+ BATTERY_SIGNALS_GROUP * 256 + 0)), //
						m(AudiBatcon.ChannelId.CONTACTOR_COMMAND,
								new UnsignedWordElement(batconChannel * BATTERY_SIGNALS_START_ADDRESS
										+ BATTERY_SIGNALS_GROUP * 256 + 1)), //
						m(AudiBatcon.ChannelId.BATTERY_RESET,
								new UnsignedWordElement(batconChannel * BATTERY_SIGNALS_START_ADDRESS
										+ BATTERY_SIGNALS_GROUP * 256 + 2)), //
						new DummyRegisterElement(
								batconChannel * BATTERY_SIGNALS_START_ADDRESS + BATTERY_SIGNALS_GROUP * 256 + 3), //
						m(AudiBatcon.ChannelId.STATUS_KL15_CAN,
								new UnsignedWordElement(batconChannel * BATTERY_SIGNALS_START_ADDRESS
										+ BATTERY_SIGNALS_GROUP * 256 + 4)), //
						m(AudiBatcon.ChannelId.FAILURE_MEMORY_DELETE,
								new UnsignedWordElement(batconChannel * BATTERY_SIGNALS_START_ADDRESS
										+ BATTERY_SIGNALS_GROUP * 256 + 5)), //
						m(AudiBatcon.ChannelId.FAILURE_MEMORY_READ,
								new UnsignedWordElement(batconChannel * BATTERY_SIGNALS_START_ADDRESS
										+ BATTERY_SIGNALS_GROUP * 256 + 6)), //
						m(AudiBatcon.ChannelId.BALANCING,
								new UnsignedWordElement(batconChannel * BATTERY_SIGNALS_START_ADDRESS
										+ BATTERY_SIGNALS_GROUP * 256 + 7)), //
						m(AudiBatcon.ChannelId.ISOLATION_MEASUREMENT_DEACTIVATION_MODE_WRITE,
								new UnsignedWordElement(batconChannel * BATTERY_SIGNALS_START_ADDRESS
										+ BATTERY_SIGNALS_GROUP * 256 + 8)), //
						m(AudiBatcon.ChannelId.SET_NEW_BATTERY_CAPACITY,
								new UnsignedWordElement(batconChannel * BATTERY_SIGNALS_START_ADDRESS
										+ BATTERY_SIGNALS_GROUP * 256 + 9)), //
						m(AudiBatcon.ChannelId.DC_CHARGE, new UnsignedWordElement(
								batconChannel * BATTERY_SIGNALS_START_ADDRESS + BATTERY_SIGNALS_GROUP * 256 + 10)) //
				),

				/*
				 * METADATA
				 */
				new FC3ReadRegistersTask(batconChannel * BATTERY_SIGNALS_START_ADDRESS + METADATA_GROUP * 256 + 0,
						Priority.HIGH, //
						m(AudiBatcon.ChannelId.BATTERYMANAGER_SOFTWAREVERSION,
								new StringWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + METADATA_GROUP * 256 + 0, 8)), //
						m(AudiBatcon.ChannelId.VW_ECU_HARDWARE_NUMBER,
								new StringWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + METADATA_GROUP * 256 + 8, 8)), //
						m(AudiBatcon.ChannelId.ASAM_ODX_FILE_VERSION,
								new StringWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + METADATA_GROUP * 256 + 16, 8)), //
						m(AudiBatcon.ChannelId.VW_ECU_HARDWARE_VERSION_NUMBER,
								new StringWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + METADATA_GROUP * 256 + 24, 8))),

				/*
				 * Status Info
				 */
				new FC3ReadRegistersTask(batconChannel * BATTERY_SIGNALS_START_ADDRESS + STATUS_INFO_GROUP * 256 + 0,
						Priority.HIGH, //
						m(AudiBatcon.ChannelId.BATTERY_TYPE,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + STATUS_INFO_GROUP * 256 + 0)), //
						new DummyRegisterElement(
								batconChannel * BATTERY_SIGNALS_START_ADDRESS + STATUS_INFO_GROUP * 256 + 1), //
						m(AudiBatcon.ChannelId.ISOLATION_MEASUREMENT_DEACTIVATION_MODE_READ,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + STATUS_INFO_GROUP * 256 + 2)), //
						new DummyRegisterElement(
								batconChannel * BATTERY_SIGNALS_START_ADDRESS + STATUS_INFO_GROUP * 256 + 3), //
						m(AudiBatcon.ChannelId.BUS_ERROR,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + STATUS_INFO_GROUP * 256 + 4)), //
						m(AudiBatcon.ChannelId.REMAINING_BUS_SIMULATION_STATUS,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + STATUS_INFO_GROUP * 256 + 5)), //
						new DummyRegisterElement(
								batconChannel * BATTERY_SIGNALS_START_ADDRESS + STATUS_INFO_GROUP * 256 + 6), //
						m(AudiBatcon.ChannelId.CAN_STATE,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + STATUS_INFO_GROUP * 256 + 7)), //
						m(AudiBatcon.ChannelId.DIAGNOSTIC_STATE, new UnsignedWordElement(
								batconChannel * BATTERY_SIGNALS_START_ADDRESS + STATUS_INFO_GROUP * 256 + 8)) //
				),

				/*
				 * CAN Values
				 */
				new FC3ReadRegistersTask(batconChannel * BATTERY_SIGNALS_START_ADDRESS + CAN_VALUES_GROUP * 256 + 1,
						Priority.HIGH, //
						m(AudiBatcon.ChannelId.ACTUAL_VOLTAGE,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + CAN_VALUES_GROUP * 256 + 1)), //
						new DummyRegisterElement(
								batconChannel * BATTERY_SIGNALS_START_ADDRESS + CAN_VALUES_GROUP * 256 + 2), //
						m(AudiBatcon.ChannelId.HV_TIMEOUT,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + CAN_VALUES_GROUP * 256 + 3)), //
						m(AudiBatcon.ChannelId.ACTUAL_CURRENT_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + CAN_VALUES_GROUP * 256 + 4)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.ACTUAL_CURRENT)
														.setNextValue(val + Constants.OFFSET_ACTUAL_CURRENT);
											}
										})), //
						m(AudiBatcon.ChannelId.ACTUAL_MAX_TEMPERATURE_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + CAN_VALUES_GROUP * 256 + 5)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(Battery.ChannelId.MAX_CELL_TEMPERATURE)
														.setNextValue(val + Constants.OFFSET_ACTUAL_TEMPERATURE);
												this.channel(AudiBatcon.ChannelId.ACTUAL_MAX_TEMPERATURE)
														.setNextValue(val + Constants.OFFSET_ACTUAL_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.ACTUAL_MIN_TEMPERATURE_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + CAN_VALUES_GROUP * 256 + 6)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(Battery.ChannelId.MIN_CELL_TEMPERATURE)
														.setNextValue(val + Constants.OFFSET_ACTUAL_TEMPERATURE);
												this.channel(AudiBatcon.ChannelId.ACTUAL_MIN_TEMPERATURE)
														.setNextValue(val + Constants.OFFSET_ACTUAL_TEMPERATURE);
											}
										}))), //

				new FC3ReadRegistersTask(batconChannel * BATTERY_SIGNALS_START_ADDRESS + CAN_VALUES_GROUP * 256 + 7,
						Priority.HIGH, //
						m(new UnsignedWordElement(
								batconChannel * BATTERY_SIGNALS_START_ADDRESS + CAN_VALUES_GROUP * 256 + 7))
								.m(Battery.ChannelId.MAX_CELL_VOLTAGE, ElementToChannelConverter.DIRECT_1_TO_1) //
								.m(AudiBatcon.ChannelId.ACTUAL_MAX_CELL_VOLTAGE,
										ElementToChannelConverter.DIRECT_1_TO_1) //
								.build()), //

				new FC3ReadRegistersTask(batconChannel * BATTERY_SIGNALS_START_ADDRESS + CAN_VALUES_GROUP * 256 + 8,
						Priority.HIGH, //
						m(new UnsignedWordElement(
								batconChannel * BATTERY_SIGNALS_START_ADDRESS + CAN_VALUES_GROUP * 256 + 8))
								.m(Battery.ChannelId.MIN_CELL_VOLTAGE, ElementToChannelConverter.DIRECT_1_TO_1) //
								.m(AudiBatcon.ChannelId.ACTUAL_MIN_CELL_VOLTAGE,
										ElementToChannelConverter.DIRECT_1_TO_1) //
								.build()), //
				new FC3ReadRegistersTask(batconChannel * BATTERY_SIGNALS_START_ADDRESS + CAN_VALUES_GROUP * 256 + 9,
						Priority.HIGH, //
						m(AudiBatcon.ChannelId.BAT_CAPACITY,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + CAN_VALUES_GROUP * 256 + 9)), //
						m(AudiBatcon.ChannelId.OPEN_CIRCUIT_VOLTAGE,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + CAN_VALUES_GROUP * 256 + 10)), //
						m(AudiBatcon.ChannelId.MAX_LIMIT_SOC,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + CAN_VALUES_GROUP * 256 + 11)), //
						m(AudiBatcon.ChannelId.MAX_CHARGE_VOLTAGE,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + CAN_VALUES_GROUP * 256 + 12)), //
						m(AudiBatcon.ChannelId.MIN_CHARGE_VOLTAGE,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + CAN_VALUES_GROUP * 256 + 13)), //
						m(AudiBatcon.ChannelId.SOC_HI_RES,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + CAN_VALUES_GROUP * 256 + 14)), //
						m(AudiBatcon.ChannelId.TEMPERATURE_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + CAN_VALUES_GROUP * 256 + 15)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.TEMPERATURE)
														.setNextValue(val - Constants.OFFSET_ACTUAL_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.OPERATION_MODE_BATTERY,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + CAN_VALUES_GROUP * 256 + 16)), //
						m(AudiBatcon.ChannelId.ENERGY_CONTENT,
								new UnsignedDoublewordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + CAN_VALUES_GROUP * 256 + 17)), //
						m(AudiBatcon.ChannelId.MIN_LIMIT_SOC,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + CAN_VALUES_GROUP * 256 + 19)), //
						m(AudiBatcon.ChannelId.USEABLE_SOC, new UnsignedWordElement(
								batconChannel * BATTERY_SIGNALS_START_ADDRESS + CAN_VALUES_GROUP * 256 + 20)) //
				),

				new FC3ReadRegistersTask(
						batconChannel * BATTERY_SIGNALS_START_ADDRESS + OPERATION_MODES_2NDL_GROUP * 256 + 1,
						Priority.HIGH, //
						m(new UnsignedWordElement(
								batconChannel * BATTERY_SIGNALS_START_ADDRESS + OPERATION_MODES_2NDL_GROUP * 256 + 1))
								.m(Battery.ChannelId.DISCHARGE_MAX_CURRENT, ElementToChannelConverter.DIRECT_1_TO_1) //
								.m(AudiBatcon.ChannelId.MAX_PERMANENT_DISCHARGE_CURRENT,
										ElementToChannelConverter.DIRECT_1_TO_1) //
								.build()), //

				new FC3ReadRegistersTask(
						batconChannel * BATTERY_SIGNALS_START_ADDRESS + OPERATION_MODES_2NDL_GROUP * 256 + 2,
						Priority.HIGH, //
						m(AudiBatcon.ChannelId.MAX_SHORT_TERM_DISCHARGE_CURRENT, new UnsignedWordElement(
								batconChannel * BATTERY_SIGNALS_START_ADDRESS + OPERATION_MODES_2NDL_GROUP * 256 + 2)) //
				),

				new FC3ReadRegistersTask(
						batconChannel * BATTERY_SIGNALS_START_ADDRESS + OPERATION_MODES_2NDL_GROUP * 256 + 3,
						Priority.HIGH, //
						m(new UnsignedWordElement(
								batconChannel * BATTERY_SIGNALS_START_ADDRESS + OPERATION_MODES_2NDL_GROUP * 256 + 3))
								.m(Battery.ChannelId.CHARGE_MAX_CURRENT, ElementToChannelConverter.DIRECT_1_TO_1) //
								.m(AudiBatcon.ChannelId.MAX_PERMANENT_CHARGE_CURRENT,
										ElementToChannelConverter.DIRECT_1_TO_1) //
								.build()), //

				new FC3ReadRegistersTask(
						batconChannel * BATTERY_SIGNALS_START_ADDRESS + OPERATION_MODES_2NDL_GROUP * 256 + 4,
						Priority.HIGH, //
						m(AudiBatcon.ChannelId.MAX_SHORT_TERM_CHARGE_CURRENT, new UnsignedWordElement(
								batconChannel * BATTERY_SIGNALS_START_ADDRESS + OPERATION_MODES_2NDL_GROUP * 256 + 4)) //
				),

				new FC3ReadRegistersTask(
						batconChannel * BATTERY_SIGNALS_START_ADDRESS + OPERATION_MODES_2NDL_GROUP * 256 + 5,
						Priority.HIGH, //
						m(new UnsignedWordElement(
								batconChannel * BATTERY_SIGNALS_START_ADDRESS + OPERATION_MODES_2NDL_GROUP * 256 + 5))
								.m(Battery.ChannelId.SOH, ElementToChannelConverter.DIRECT_1_TO_1) //
								.m(AudiBatcon.ChannelId.USEABLE_CAPCITY_SOH, ElementToChannelConverter.DIRECT_1_TO_1) //
								.build()), //

				new FC3ReadRegistersTask(
						batconChannel * BATTERY_SIGNALS_START_ADDRESS + OPERATION_MODES_2NDL_GROUP * 256 + 6,
						Priority.HIGH, //
						m(AudiBatcon.ChannelId.MAX_ALLOWED_CELL_VOLTAGE,
								new UnsignedWordElement(batconChannel * BATTERY_SIGNALS_START_ADDRESS
										+ OPERATION_MODES_2NDL_GROUP * 256 + 6)), //
						m(AudiBatcon.ChannelId.MIN_ALLOWED_CELL_VOLTAGE,
								new UnsignedWordElement(batconChannel * BATTERY_SIGNALS_START_ADDRESS
										+ OPERATION_MODES_2NDL_GROUP * 256 + 7)), //
						m(AudiBatcon.ChannelId.MAX_ALLOWED_TEMPERATURE,
								new UnsignedWordElement(batconChannel * BATTERY_SIGNALS_START_ADDRESS
										+ OPERATION_MODES_2NDL_GROUP * 256 + 8)), //
						m(AudiBatcon.ChannelId.MIN_ALLOWED_TEMPERATURE,
								new UnsignedWordElement(batconChannel * BATTERY_SIGNALS_START_ADDRESS
										+ OPERATION_MODES_2NDL_GROUP * 256 + 9))), //

				new FC3ReadRegistersTask(
						batconChannel * BATTERY_SIGNALS_START_ADDRESS + OPERATION_MODES_2NDL_GROUP * 256 + 10,
						Priority.HIGH, //
						m(new UnsignedWordElement(
								batconChannel * BATTERY_SIGNALS_START_ADDRESS + OPERATION_MODES_2NDL_GROUP * 256 + 10))
								.m(Battery.ChannelId.CHARGE_MAX_VOLTAGE, ElementToChannelConverter.DIRECT_1_TO_1) //
								.m(AudiBatcon.ChannelId.MAX_ALLOWED_BATTERY_VOLTAGE,
										ElementToChannelConverter.DIRECT_1_TO_1) //
								.build()), //

				new FC3ReadRegistersTask(
						batconChannel * BATTERY_SIGNALS_START_ADDRESS + OPERATION_MODES_2NDL_GROUP * 256 + 11,
						Priority.HIGH, //
						m(new UnsignedWordElement(
								batconChannel * BATTERY_SIGNALS_START_ADDRESS + OPERATION_MODES_2NDL_GROUP * 256 + 11))
								.m(Battery.ChannelId.DISCHARGE_MIN_VOLTAGE, ElementToChannelConverter.DIRECT_1_TO_1) //
								.m(AudiBatcon.ChannelId.MIN_ALLOWED_BATTERY_VOLTAGE,
										ElementToChannelConverter.DIRECT_1_TO_1) //
								.build()), //

				new FC3ReadRegistersTask(
						batconChannel * BATTERY_SIGNALS_START_ADDRESS + OPERATION_MODES_2NDL_GROUP * 256 + 12,
						Priority.HIGH, //
						m(AudiBatcon.ChannelId.NEW_BATTERY_CAPACITY, new UnsignedWordElement(
								batconChannel * BATTERY_SIGNALS_START_ADDRESS + OPERATION_MODES_2NDL_GROUP * 256 + 12)) //
				),

				/*
				 * Failure Signals
				 */
				new FC3ReadRegistersTask(
						batconChannel * BATTERY_SIGNALS_START_ADDRESS + FAILURE_SIGNALS_GROUP * 256 + 0, Priority.HIGH, //
						m(new BitsWordElement(
								batconChannel * BATTERY_SIGNALS_START_ADDRESS + FAILURE_SIGNALS_GROUP * 256 + 0, this) //
								.bit(0, AudiBatcon.ChannelId.CELL_OVER_CHARGED) //
								.bit(1, AudiBatcon.ChannelId.CELL_DEEP_DISCHARGED) //
								.bit(2, AudiBatcon.ChannelId.MAX_CELL_TEMPERATURE_HIGH_ALARM) //
						), //

						m(new BitsWordElement(
								batconChannel * BATTERY_SIGNALS_START_ADDRESS + FAILURE_SIGNALS_GROUP * 256 + 1, this) //
								.bit(0, AudiBatcon.ChannelId.MAX_CELL_VOLTAGE_HIGH_ALARM) //
								.bit(1, AudiBatcon.ChannelId.MIN_CELL_VOLTAGE_LOW_ALARM) //
								.bit(2, AudiBatcon.ChannelId.MAX_SHORT_TERM_CHARGE_CURRENT_HIGH_ALARM) //
								.bit(3, AudiBatcon.ChannelId.MAX_SHORT_TERM_DISCHARGE_CURRENT_HIGH_ALARM) //
								.bit(4, AudiBatcon.ChannelId.MAX_PERMANENT_CHARGE_CURRENT_HIGH_ALARM) //
								.bit(5, AudiBatcon.ChannelId.MAX_PERMANENT_DISCHARGE_CURRENT_HIGH_ALARM) //
								.bit(6, AudiBatcon.ChannelId.CELL_TEMPERATURE_HIGH_LOW_ALARM_5K) //
								.bit(7, AudiBatcon.ChannelId.CELL_TEMPERATURE_HIGH_LOW_ALARM) //
								.bit(8, AudiBatcon.ChannelId.BATTERY_OVER_UNDER_VOLTAGE) //
								.bit(9, AudiBatcon.ChannelId.DISCHARGE_CURRENT_1NDL_TOO_HIGH) //
								.bit(10, AudiBatcon.ChannelId.CHARGE_CURRENT_1NDL_TOO_HIGH) //
						), //

						m(new BitsWordElement(
								batconChannel * BATTERY_SIGNALS_START_ADDRESS + FAILURE_SIGNALS_GROUP * 256 + 2, this) //
								.bit(0, AudiBatcon.ChannelId.ISOLATION_FAILURE) //
								.bit(1, AudiBatcon.ChannelId.FAILURE_PILOT_LINE) //
								.bit(2, AudiBatcon.ChannelId.POWER_DERATING) //
								.bit(3, AudiBatcon.ChannelId.FAILURE_BATTERY_FUSE) //
								.bit(4, AudiBatcon.ChannelId.FAILURE_COLD_START_POWER) //
								.bit(5, AudiBatcon.ChannelId.CONTACTOR_CLOSING_NOT_POSSIBLE) //
								.bit(6, AudiBatcon.ChannelId.FAILURE_SD) //
								.bit(7, AudiBatcon.ChannelId.NO_COMPONENT_FUNCTION) //
								.bit(8, AudiBatcon.ChannelId.MAIN_CONTACTOR_WELDED) //
								.bit(9, AudiBatcon.ChannelId.BATTERY_FAILURE_DIAGNOSTIC_NEEDED) //
						)),

				/*
				 * Balancing
				 */
				new FC3ReadRegistersTask(batconChannel * BATTERY_SIGNALS_START_ADDRESS + BALANCING_GROUP * 256 + 0,
						Priority.HIGH, //
						m(AudiBatcon.ChannelId.ACTUAL_BALANCING_STATUS,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + BALANCING_GROUP * 256 + 0)), //
						m(AudiBatcon.ChannelId.BALANCING_MONITORING,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + BALANCING_GROUP * 256 + 1)), //
						m(AudiBatcon.ChannelId.MAX_ALLOWED_CURRENT_BALANCING,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + BALANCING_GROUP * 256 + 2)), //
						m(AudiBatcon.ChannelId.STATUS_CELL_VOLTAGE_DIFFERENCE,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + BALANCING_GROUP * 256 + 3)), //
						m(AudiBatcon.ChannelId.BALANCING_MESSAGE, new UnsignedWordElement(
								batconChannel * BATTERY_SIGNALS_START_ADDRESS + BALANCING_GROUP * 256 + 4)) //
				),

				/*
				 * CAN Values High Resolution
				 */
				new FC3ReadRegistersTask(
						batconChannel * BATTERY_SIGNALS_START_ADDRESS + CAN_VALUES_HIGH_RESOLUTION_GROUP * 256 + 0,
						Priority.HIGH, //
						m(new UnsignedDoublewordElement(batconChannel * BATTERY_SIGNALS_START_ADDRESS
								+ CAN_VALUES_HIGH_RESOLUTION_GROUP * 256 + 0))
								.m(Battery.ChannelId.VOLTAGE, ElementToChannelConverter.SCALE_FACTOR_MINUS_3) //
								.m(AudiBatcon.ChannelId.VOLTAGE_HIGH_RES,
										ElementToChannelConverter.SCALE_FACTOR_MINUS_3) //
								.build()), //

				new FC3ReadRegistersTask(
						batconChannel * BATTERY_SIGNALS_START_ADDRESS + CAN_VALUES_HIGH_RESOLUTION_GROUP * 256 + 2,
						Priority.HIGH, //
						m(AudiBatcon.ChannelId.CURRENT_HIGH_RES_WITHOUT_OFFSET,
								new UnsignedDoublewordElement(batconChannel * BATTERY_SIGNALS_START_ADDRESS
										+ CAN_VALUES_HIGH_RESOLUTION_GROUP * 256 + 2).onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(Battery.ChannelId.CURRENT)
														.setNextValue(val + Constants.OFFSET_ACTUAL_CURRENT);
												this.channel(AudiBatcon.ChannelId.CURRENT_HIGH_RES)
														.setNextValue(val + Constants.OFFSET_ACTUAL_CURRENT);
											}
										}),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3)), //

				new FC3ReadRegistersTask(
						batconChannel * BATTERY_SIGNALS_START_ADDRESS + CAN_VALUES_HIGH_RESOLUTION_GROUP * 256 + 4,
						Priority.HIGH, //
						m(new UnsignedDoublewordElement(batconChannel * BATTERY_SIGNALS_START_ADDRESS
								+ CAN_VALUES_HIGH_RESOLUTION_GROUP * 256 + 4))
								.m(Battery.ChannelId.SOC, ElementToChannelConverter.SCALE_FACTOR_MINUS_3) //
								.m(AudiBatcon.ChannelId.SOC_HI_RES_HIGH_RES,
										ElementToChannelConverter.SCALE_FACTOR_MINUS_3) //
								.build()), //
				new FC3ReadRegistersTask(
						batconChannel * BATTERY_SIGNALS_START_ADDRESS + CAN_VALUES_HIGH_RESOLUTION_GROUP * 256 + 6,
						Priority.HIGH, //
						m(AudiBatcon.ChannelId.TEMPERATURE_HIGH_RES_WITHOUT_OFFSET,
								new UnsignedDoublewordElement(batconChannel * BATTERY_SIGNALS_START_ADDRESS
										+ CAN_VALUES_HIGH_RESOLUTION_GROUP * 256 + 6).onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.TEMPERATURE_HIGH_RES)
														.setNextValue(val + Constants.OFFSET_ACTUAL_TEMPERATURE);
											}
										}),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3)), //

				new FC3ReadRegistersTask(
						batconChannel * BATTERY_SIGNALS_START_ADDRESS + CAN_VALUES_HIGH_RESOLUTION_GROUP * 256 + 8,
						Priority.HIGH, //
						m(new UnsignedDoublewordElement(batconChannel * BATTERY_SIGNALS_START_ADDRESS
								+ CAN_VALUES_HIGH_RESOLUTION_GROUP * 256 + 8))
								.m(Battery.ChannelId.CAPACITY, ElementToChannelConverter.SCALE_FACTOR_MINUS_3) //
								.m(AudiBatcon.ChannelId.CAPACITY_HIGH_RES,
										ElementToChannelConverter.SCALE_FACTOR_MINUS_3) //
								.build()), //

				/**
				 * CBEV - Expert Signals
				 */

				/*
				 * Isolation Resistance
				 */
				new FC3ReadRegistersTask(
						batconChannel * BATTERY_SIGNALS_START_ADDRESS + ISOLATION_RESISTANCE_GROUP * 256 + 0,
						Priority.HIGH, //
						m(AudiBatcon.ChannelId.ISOLATION_RESISTANCE_SYSTEM_PLUS,
								new UnsignedWordElement(batconChannel * BATTERY_SIGNALS_START_ADDRESS
										+ ISOLATION_RESISTANCE_GROUP * 256 + 0),
								ElementToChannelConverter.SCALE_FACTOR_1), //
						m(AudiBatcon.ChannelId.ISOLATION_RESISTANCE_SYSTEM_MINUS,
								new UnsignedWordElement(batconChannel * BATTERY_SIGNALS_START_ADDRESS
										+ ISOLATION_RESISTANCE_GROUP * 256 + 1),
								ElementToChannelConverter.SCALE_FACTOR_1), //
						m(AudiBatcon.ChannelId.ISOLATION_RESISTANCE_BATTERY_PLUS,
								new UnsignedWordElement(batconChannel * BATTERY_SIGNALS_START_ADDRESS
										+ ISOLATION_RESISTANCE_GROUP * 256 + 2),
								ElementToChannelConverter.SCALE_FACTOR_1), //
						m(AudiBatcon.ChannelId.ISOLATION_RESISTANCE_BATTERY_MINUS,
								new UnsignedWordElement(batconChannel * BATTERY_SIGNALS_START_ADDRESS
										+ ISOLATION_RESISTANCE_GROUP * 256 + 3),
								ElementToChannelConverter.SCALE_FACTOR_1) //
				),

				/*
				 * Temperatures
				 */
				new FC3ReadRegistersTask(batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 0,
						Priority.HIGH, //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_1_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 0)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_1)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_2_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 1)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_2)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_3_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 2)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_3)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_4_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 3)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_4)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_5_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 4)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_5)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_6_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 5)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_6)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_7_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 6)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_7)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_8_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 7)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_8)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_9_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 8)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_9)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_10_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 9)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_10)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_11_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 10)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_11)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_12_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 11)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_12)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //

						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_13_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 12)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_13)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_14_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 13)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_14)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_15_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 14)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_15)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_16_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 15)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_16)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_17_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 16)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_17)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_18_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 17)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_18)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_19_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 18)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_19)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_20_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 19)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_20)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_21_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 20)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_21)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_22_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 21)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_22)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_23_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 22)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_23)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_24_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 23)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_24)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_25_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 24)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_25)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_26_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 25)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_26)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_27_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 26)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_27)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_28_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 27)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_28)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_29_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 28)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_29)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_30_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 29)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_30)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_31_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 30)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_31)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_32_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 31)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_32)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_33_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 32)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_33)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_34_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 33)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_34)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_35_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 34)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_35)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_36_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 35)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_36)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_37_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 36)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_37)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_38_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 37)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_38)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_39_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 38)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_39)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_40_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 39)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_40)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_41_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 40)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_41)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_42_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 41)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_42)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_43_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 42)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_43)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_44_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 43)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_44)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_45_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 44)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_45)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_46_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 45)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_46)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_47_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 46)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_47)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_48_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 47)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_48)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_49_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 48)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_49)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_50_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 49)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_50)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_51_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 50)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_51)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_52_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 51)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_52)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_53_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 52)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_53)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_54_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 53)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_54)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_55_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 54)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_55)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_56_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 55)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_56)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_57_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 56)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_57)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_58_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 57)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_58)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_59_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 58)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_59)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_60_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 59)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_60)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_61_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 60)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_61)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_62_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 61)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_62)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_63_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 62)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_63)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_64_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 63)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_64)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_65_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 64)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_65)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_66_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 65)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_66)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_67_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 66)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_67)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_68_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 67)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_68)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_69_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 68)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_69)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_70_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 69)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_70)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_71_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 70)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_71)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_72_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 71)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_SENOSR_72)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //

						m(AudiBatcon.ChannelId.HIGH_VOLTAGE_BATTERY_MAXIMUM_TEMPERATURE_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 72)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(
														AudiBatcon.ChannelId.HIGH_VOLTAGE_BATTERY_MAXIMUM_TEMPERATURE)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.HIGH_VOLTAGE_BATTERY_MINIMUM_TEMPERATURE_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 73)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(
														AudiBatcon.ChannelId.HIGH_VOLTAGE_BATTERY_MINIMUM_TEMPERATURE)
														.setNextValue(
																val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})), //
						m(AudiBatcon.ChannelId.BATTERY_TEMPERATURE_WITHOUT_OFFSET,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + TEMPERATURES_GROUP * 256 + 74)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.BATTERY_TEMPERATURE).setNextValue(
														val + Constants.OFFSET_ACTUAL_BATTERY_TEMPERATURE);
											}
										})) //

				),

				/*
				 * State of Charge
				 */

				new FC3ReadRegistersTask(
						batconChannel * BATTERY_SIGNALS_START_ADDRESS + STATE_OF_CHARGE_GROUP * 256 + 0, Priority.HIGH,
						m(AudiBatcon.ChannelId.USER_SOC,
								new UnsignedWordElement(batconChannel * BATTERY_SIGNALS_START_ADDRESS
										+ STATE_OF_CHARGE_GROUP * 256 + 0)),
						m(AudiBatcon.ChannelId.MAXIMUM_CELL_STATE_OF_CHARGE,
								new UnsignedWordElement(batconChannel * BATTERY_SIGNALS_START_ADDRESS
										+ STATE_OF_CHARGE_GROUP * 256 + 1)), //
						m(AudiBatcon.ChannelId.MINIMUM_CELL_STATE_OF_CHARGE, new UnsignedWordElement(
								batconChannel * BATTERY_SIGNALS_START_ADDRESS + STATE_OF_CHARGE_GROUP * 256 + 2)) //
				),

				/*
				 * Voltages
				 */

				new FC3ReadRegistersTask(batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_GROUP * 256 + 0,
						Priority.HIGH,
						m(AudiBatcon.ChannelId.VOLTAGE_TERMINAL_30_WITHOUT_OFFSET,
								new UnsignedDoublewordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_GROUP * 256 + 0)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.VOLTAGE_TERMINAL_30)
														.setNextValue((val - Constants.OFFSET_VOLTAGE_TERMINAL_30) * 5);
											}
										}),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_2), //
						m(AudiBatcon.ChannelId.HIGH_VOLTAGE_SYSTEM_VOLTAGE,
								new UnsignedDoublewordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_GROUP * 256 + 2),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_1), //
						m(AudiBatcon.ChannelId.SUM_OF_CELL_VOLTAGE_INTERN,
								new UnsignedDoublewordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_GROUP * 256 + 4),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.MAXIMUM_CELL_VOLTAGE,
								new UnsignedDoublewordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_GROUP * 256 + 6),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.MINIMUM_CELL_VOLTAGE,
								new UnsignedDoublewordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_GROUP * 256 + 8),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3)),

				/*
				 * Cell Voltages
				 */

				new FC3ReadRegistersTask(batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 0,
						Priority.HIGH,

						m(AudiBatcon.ChannelId.VOLTAGE_CELL_1,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 0),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_2,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 1),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_3,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 2),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_4,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 3),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_5,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 4),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_6,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 5),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_7,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 6),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_8,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 7),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_9,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 8),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_10,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 9),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_11,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 10),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_12,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 11),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_13,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 12),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_14,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 13),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_15,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 14),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_16,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 15),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_17,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 16),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_18,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 17),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_19,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 18),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_20,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 19),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_21,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 20),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_22,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 21),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_23,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 22),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_24,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 23),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_25,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 24),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_26,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 25),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_27,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 26),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_28,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 27),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_29,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 28),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_30,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 29),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_31,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 30),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_32,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 31),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_33,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 32),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_34,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 33),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_35,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 34),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_36,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 35),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_37,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 36),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_38,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 37),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_39,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 38),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_40,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 39),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_41,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 40),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_42,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 41),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_43,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 42),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_44,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 43),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_45,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 44),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_46,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 45),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_47,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 46),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_48,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 47),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_49,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 48),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_50,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 49),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_51,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 50),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_52,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 51),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_53,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 52),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_54,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 53),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_55,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 54),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_56,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 55),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_57,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 56),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_58,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 57),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_59,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 58),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_60,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 59),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_61,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 60),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_62,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 61),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_63,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 62),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_64,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 63),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_65,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 64),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_66,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 65),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_67,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 66),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_68,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 67),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_69,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 68),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_70,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 69),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_71,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 70),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_72,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 71),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_73,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 72),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_74,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 73),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_75,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 74),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_76,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 75),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_77,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 76),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_78,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 77),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_79,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 78),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_80,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 79),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_81,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 80),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_82,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 81),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_83,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 82),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_84,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 83),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_85,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 84),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_86,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 85),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_87,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 86),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_88,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 87),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_89,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 88),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_90,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 89),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_91,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 90),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_92,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 91),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_93,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 92),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_94,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 93),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_95,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 94),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_96,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 95),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_97,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 96),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_98,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 97),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_99,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 98),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_100,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 99),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_101,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 100),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_102,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 101),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_103,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 102),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_104,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 103),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_105,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 104),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_106,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 105),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_107,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 106),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3), //
						m(AudiBatcon.ChannelId.VOLTAGE_CELL_108,
								new UnsignedWordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + VOLTAGE_CELL_GROUP * 256 + 107),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3) // )

				),

				/*
				 * Pack Current
				 */

				new FC3ReadRegistersTask(batconChannel * BATTERY_SIGNALS_START_ADDRESS + PACK_CURRENT_GROUP * 256 + 0,
						Priority.HIGH, //
						m(AudiBatcon.ChannelId.PACK_CURRENT_1_WITHOUT_OFFSET,
								new UnsignedDoublewordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + PACK_CURRENT_GROUP * 256 + 0)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.PACK_CURRENT_1)
														.setNextValue(val + Constants.OFFSET_PACK_CURRENT);
											}
										}),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_2), //
						m(AudiBatcon.ChannelId.PACK_CURRENT_2_WITHOUT_OFFSET,
								new UnsignedDoublewordElement(
										batconChannel * BATTERY_SIGNALS_START_ADDRESS + PACK_CURRENT_GROUP * 256 + 2)
										.onUpdateCallback(val -> {
											if (val == null) {
												return;
											} else {
												this.channel(AudiBatcon.ChannelId.PACK_CURRENT_2)
														.setNextValue(val + Constants.OFFSET_PACK_CURRENT);
											}
										}),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_2)

				),

				/*
				 * Isolation resistance Status
				 */

				new FC3ReadRegistersTask(
						batconChannel * BATTERY_SIGNALS_START_ADDRESS + ISOLATION_RESISTANCE_STATUS_GROUP * 256 + 0,
						Priority.HIGH, //
						m(AudiBatcon.ChannelId.INSOLATION_RESISTANCE_SYSTEM_PLUS,
								new UnsignedWordElement(batconChannel * BATTERY_SIGNALS_START_ADDRESS
										+ ISOLATION_RESISTANCE_STATUS_GROUP * 256 + 0)),
						m(AudiBatcon.ChannelId.INSOLATION_RESISTANCE_SYSTEM_MINUS,
								new UnsignedWordElement(batconChannel * BATTERY_SIGNALS_START_ADDRESS
										+ ISOLATION_RESISTANCE_STATUS_GROUP * 256 + 1)), //
						m(AudiBatcon.ChannelId.INSOLATION_RESISTANCE_BATTERY_PLUS,
								new UnsignedWordElement(batconChannel * BATTERY_SIGNALS_START_ADDRESS
										+ ISOLATION_RESISTANCE_STATUS_GROUP * 256 + 2)), //
						m(AudiBatcon.ChannelId.INSOLATION_RESISTANCE_BATTERY_MINUS,
								new UnsignedWordElement(batconChannel * BATTERY_SIGNALS_START_ADDRESS
										+ ISOLATION_RESISTANCE_STATUS_GROUP * 256 + 3)) //

				)

		);

	}

	@Override
	public String debugLog() {
		return "SoC: " + this.channel(AudiBatcon.ChannelId.SOC_HI_RES).value() //
				+ " | Voltage: " + this.channel(AudiBatcon.ChannelId.ACTUAL_VOLTAGE).value() //
				+ " | Current: " + this.channel(AudiBatcon.ChannelId.ACTUAL_CURRENT).value() //
				+ " | MaxChargeCurrent: " + this.channel(Battery.ChannelId.CHARGE_MAX_CURRENT).value() //
				+ " | MaxDischargeCurrent: " + this.channel(Battery.ChannelId.DISCHARGE_MAX_CURRENT).value() //
				+ " | Operation Mode: " + this.channel(AudiBatcon.ChannelId.OPERATION_MODE_BATTERY).value().asEnum() //
				+ " | State: " + this.stateMachine.getCurrentState().asCamelCase() //
		;
	}

	private AtomicReference<StartStop> startStopTarget = new AtomicReference<StartStop>(StartStop.UNDEFINED);

	@Override
	public void setStartStop(StartStop value) {
		this.startStopTarget.set(value);

	}

	@Override
	public ModbusSlaveTable getModbusSlaveTable(AccessMode accessMode) {
		return new ModbusSlaveTable(//
				OpenemsComponent.getModbusSlaveNatureTable(accessMode), //
				Battery.getModbusSlaveNatureTable(accessMode) //
		);
	}

	@Override
	public StartStop getStartStopTarget() {
		switch (this.config.startStop()) {
		case AUTO:
			// read StartStop-Channel
			return this.startStopTarget.get();

		case START:
			// force START
			return StartStop.START;

		case STOP:
			// force STOP
			return StartStop.STOP;
		}
		assert false;
		return StartStop.UNDEFINED; // can never happen
	}

	@Override
	public void handleRemainingBusSimulation() {
		if (this.config.remainingBusSimulation() == RemainingBusSimulationCommand.ON
				&& this.getRemainingBusSimulationStatus() == RemainingBusSimulationStatus.INACTIVE) {
			this.setRemainingBusSimulationOn();
		} else if (this.config.remainingBusSimulation() == RemainingBusSimulationCommand.OFF
				&& this.getRemainingBusSimulationStatus() == RemainingBusSimulationStatus.ACTIVE) {
			this.setRemainingBusSimulationOff();
		}
	}
}
