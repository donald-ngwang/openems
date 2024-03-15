package io.openems.edge.battery.audibatcon;

import org.junit.Test;

import io.openems.edge.battery.audibatcon.enums.BatteryType;
import io.openems.edge.battery.audibatcon.enums.RemainingBusSimulationCommand;
import io.openems.edge.bridge.modbus.test.DummyModbusBridge;
import io.openems.edge.common.startstop.StartStopConfig;
import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.common.test.DummyComponentManager;
import io.openems.edge.common.test.DummyConfigurationAdmin;

public class MyModbusDeviceTest {

	private static final String COMPONENT_ID = "component0";
	private static final String MODBUS_ID = "modbus0";

	@Test
	public void test() throws Exception {
		new ComponentTest(new AudiBatconImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", new DummyComponentManager()) //
				.addReference("setModbus", new DummyModbusBridge(MODBUS_ID)) //
				.activate(MyConfig.create() //
						.setId(COMPONENT_ID) //
						.setModbusId(MODBUS_ID) //
						.setStartStop(StartStopConfig.START)//
						.setRemainingBusSimulationCommand(RemainingBusSimulationCommand.OFF)//
						.setBatteryType(BatteryType.CBEV)//
						.build())
				.next(new TestCase());
	}

}
