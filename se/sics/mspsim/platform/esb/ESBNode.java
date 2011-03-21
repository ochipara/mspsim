/**
 * Copyright (c) 2007, Swedish Institute of Computer Science.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the Institute nor the names of its contributors
 *    may be used to endorse or promote products derived from this software
 *    without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE INSTITUTE AND CONTRIBUTORS ``AS IS'' AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE INSTITUTE OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 * OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 *
 * This file is part of MSPSim.
 *
 * $Id$
 *
 * -----------------------------------------------------------------
 *
 * ESBNode
 *
 * Author  : Joakim Eriksson
 * Created : Sun Oct 21 22:00:00 2007
 * Updated : $Date$
 *           $Revision$
 */

package se.sics.mspsim.platform.esb;
import java.io.IOException;

import se.sics.mspsim.chip.Beeper;
import se.sics.mspsim.chip.Leds;
import se.sics.mspsim.chip.TR1001;
import se.sics.mspsim.config.MSP430f1611Config;
import se.sics.mspsim.core.IOPort;
import se.sics.mspsim.core.IOUnit;
import se.sics.mspsim.core.MSP430;
import se.sics.mspsim.core.MSP430Config;
import se.sics.mspsim.core.PortListener;
import se.sics.mspsim.core.USART;
import se.sics.mspsim.extutil.jfreechart.DataChart;
import se.sics.mspsim.extutil.jfreechart.DataSourceSampler;
import se.sics.mspsim.platform.GenericNode;
import se.sics.mspsim.ui.SerialMon;
import se.sics.mspsim.util.ArgumentManager;
import se.sics.mspsim.util.OperatingModeStatistics;

public class ESBNode extends GenericNode implements PortListener {

  public static final boolean DEBUG = false;

  public static final int PIR_PIN = 3;
  public static final int VIB_PIN = 4;
  // Port 2.
  public static final int BUTTON_PIN = 7;

  private IOPort port1;
  private IOPort port2;
  private IOPort port5;

  private static final int[] LEDS = { 0xff2020, 0xffff00, 0x40ff40 };
  public static final int RED_LED = 0x01;
  public static final int GREEN_LED = 0x02;
  public static final int YELLOW_LED = 0x04;
  public static final int BEEPER = 0x08;

  private Leds leds;
  public boolean redLed;
  public boolean greenLed;
  public boolean yellowLed;

  private TR1001 radio;
  private Beeper beeper;
  private ESBGui gui;

  /**
   * Creates a new <code>ESBNode</code> instance.
   *
   */
  public ESBNode() {
      /* this should be a config for the MSP430f149 */
      super("ESB", new MSP430f1611Config());
  }

  public Leds getLeds() {
      return leds;
  }

  public Beeper getBeeper() {
      return beeper;
  }

  public void setPIR(boolean hi) {
    port1.setPinState(PIR_PIN, hi ? IOPort.PIN_HI : IOPort.PIN_LOW);
  }

  public void setVIB(boolean hi) {
    port1.setPinState(VIB_PIN, hi ? IOPort.PIN_HI : IOPort.PIN_LOW);
  }

  public void setButton(boolean hi) {
    port2.setPinState(BUTTON_PIN, hi ? IOPort.PIN_HI : IOPort.PIN_LOW);
  }

  public boolean getDebug() {
    return cpu.getDebug();
  }
  public void setDebug(boolean debug) {
    cpu.setDebug(debug);
  }

  public void portWrite(IOPort source, int data) {
    //    System.out.println("ESB: Writing to port: " + data);
    if (source == port2) {
//       System.out.println("ESBNode.PORT2: 0x" + Integer.toString(data,16));
      redLed = (data & RED_LED) == 0;
      greenLed = (data & GREEN_LED) == 0;
      yellowLed = (data & YELLOW_LED) == 0;
      leds.setLeds((greenLed ? 4 : 0) + (yellowLed ? 2 : 0) + (redLed ? 1 : 0));
      beeper.beepOn((data & BEEPER) != 0);

    } else if (source == port5) {
      if ((data & 0xc0) == 0xc0) {
        radio.setMode(TR1001.MODE_RX_ON);
      } else if ((data & 0xc0) == 0x40) {
        radio.setMode(TR1001.MODE_TXRX_ON);
      } else {
        radio.setMode(TR1001.MODE_TXRX_OFF);
      }
    }
  }

  public void setupNodePorts() {
    IOUnit unit = cpu.getIOUnit("Port 2");
    if (unit instanceof IOPort) {
      port2 = (IOPort) unit;
      port2.setPortListener(this);
    }

    unit = cpu.getIOUnit("Port 1");
    if (unit instanceof IOPort) {
      port1 = (IOPort) unit;
    }

    unit = cpu.getIOUnit("Port 5");
    if (unit instanceof IOPort) {
      port5 = (IOPort) unit;
      port5.setPortListener(this);
    }

    IOUnit usart0 = cpu.getIOUnit("USART 0");
    if (usart0 instanceof USART) {
      radio = new TR1001(cpu, (USART) usart0);
    }
    leds = new Leds(cpu, LEDS);
    beeper = new Beeper(cpu);
  }

  public void setupNode() {
    setupNodePorts();

    if (stats != null) {
      stats.addMonitor(this);
      stats.addMonitor(radio);
      stats.addMonitor(cpu);
    }
    
    if (!config.getPropertyAsBoolean("nogui", true)) {
      setupGUI();

      beeper.setSoundEnabled(true);

      // Add some windows for listening to serial output
      IOUnit usart = cpu.getIOUnit("USART 1");
      if (usart instanceof USART) {
        SerialMon serial = new SerialMon((USART)usart, "USART1 Port Output");
        registry.registerComponent("serialgui", serial);
      }

      if (stats != null) {
        // A HACK for some "graphs"!!!
        DataChart dataChart =  new DataChart(registry, "Duty Cycle", "Duty Cycle");
        registry.registerComponent("dutychart", dataChart);
        DataSourceSampler dss = dataChart.setupChipFrame(cpu);
        dataChart.addDataSource(dss, "LEDS", stats.getDataSource(getID(), 0, OperatingModeStatistics.OP_INVERT));
        dataChart.addDataSource(dss, "Listen", stats.getDataSource(radio.getID(), TR1001.MODE_RX_ON));
        dataChart.addDataSource(dss, "Transmit", stats.getDataSource(radio.getID(), TR1001.MODE_TXRX_ON));
        dataChart.addDataSource(dss, "CPU", stats.getDataSource(cpu.getID(), MSP430.MODE_ACTIVE));
      }
    }
  }

  public void setupGUI() {
    if (gui == null) {
      gui = new ESBGui(this);
      registry.registerComponent("nodegui", gui);
    }
  }

  public int getModeMax() {
    return 0;
  }

  public static void main(String[] args) throws IOException {
    ESBNode node = new ESBNode();
    ArgumentManager config = new ArgumentManager();
    config.handleArguments(args);
    node.setupArgs(config);
  }

}
