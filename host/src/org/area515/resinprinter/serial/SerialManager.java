package org.area515.resinprinter.serial;

import gnu.io.CommPortIdentifier;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.area515.resinprinter.display.AlreadyAssignedException;
import org.area515.resinprinter.display.InappropriateDeviceException;
import org.area515.resinprinter.printer.MachineConfig.ComPortSettings;
import org.area515.resinprinter.printer.Printer;
import org.area515.resinprinter.projector.ProjectorModel;
import org.area515.resinprinter.server.HostProperties;
import org.area515.util.IOUtilities;

public class SerialManager {
	private static SerialManager INSTANCE = null;
	public static final String FIRST_AVAILABLE_PORT = "First available serial port";
	public static final String AUTO_DETECT_3D_FIRMWARE = "Autodetect 3d printer firmware";
	public static final String AUTO_DETECT_PROJECTOR = "Autodetect projector";
	
	private ConcurrentHashMap<SerialCommunicationsPort, Printer> printersBySerialPort = new ConcurrentHashMap<SerialCommunicationsPort, Printer>();
	private enum ComPortReservation {
		Projector,
		PrinterFirmware
	}
	
	/** Milliseconds to block while waiting for port open */
	public static final int TIME_OUT = 2000;
	public static final int CPU_LIMITING_DELAY = 200;
	
	public static class DetectedResources {
		SerialCommunicationsPort comPort;
		ProjectorModel model;
	}
	
	public static SerialManager Instance() {
		if (INSTANCE == null) {
			INSTANCE = new SerialManager();
		}
		return INSTANCE;
	}
	
	private SerialManager() {
	}
	
	public ProjectorModel getProjectorModel(SerialCommunicationsPort currentIdentifier, ComPortSettings newComPortSettings) {
		ProjectorModel currentModel = null;
		for (ProjectorModel model : HostProperties.Instance().getAutodetectProjectors()) {
			try {
				currentIdentifier.open(AUTO_DETECT_PROJECTOR, TIME_OUT, newComPortSettings);
				if (model.autodetect(currentIdentifier)) {
					currentModel = model;
					break;
				}
			} catch (AlreadyAssignedException | InappropriateDeviceException e) {
				return null;
			} finally {
				currentIdentifier.close();
			}
		}
		
		return currentModel;
	}
	
	public boolean is3dFirmware(SerialCommunicationsPort currentIdentifier, ComPortSettings newComPortSettings) {
		try {
			currentIdentifier.open(AUTO_DETECT_3D_FIRMWARE, TIME_OUT, newComPortSettings);
			
			//Marlin and other firmware sends garbage on a new connect.
			String chitChat = IOUtilities.readWithTimeout(currentIdentifier, TIME_OUT, CPU_LIMITING_DELAY);
			
			//Send an absolute positioning gcode and determine if the other end responds with an ok. If so, it's probably 3dFirmware.
			currentIdentifier.write("G91\r\n".getBytes());
			
			String detection = IOUtilities.readWithTimeout(currentIdentifier, TIME_OUT, CPU_LIMITING_DELAY);
			String lines[] = detection.split("\n");
			if (lines.length == 0) {
				return false;
			}
			if (lines[lines.length - 1].matches("[Oo][Kk].*")) {
				return true;
			}
			return false;
		} catch (InterruptedException | AlreadyAssignedException | InappropriateDeviceException | IOException e) {
			return false;
		} finally {
			if (currentIdentifier != null) {
				currentIdentifier.close();
			}
		}
	}
	
	private DetectedResources detectResourcesAndAssignPort(Printer printer, SerialCommunicationsPort identifier, ComPortSettings newComPortSettings, ComPortReservation reservationStyle) throws AlreadyAssignedException, InappropriateDeviceException {
		DetectedResources resources = new DetectedResources();
		String identifierName = identifier.getName();
		if (identifierName.equals(AUTO_DETECT_3D_FIRMWARE) && reservationStyle != ComPortReservation.PrinterFirmware) {
			throw new InappropriateDeviceException("It doesn't make sense to use:" + AUTO_DETECT_3D_FIRMWARE + " with:" + reservationStyle);
		}
		if (identifierName.equals(AUTO_DETECT_PROJECTOR) && reservationStyle != ComPortReservation.Projector) {
			throw new InappropriateDeviceException("It doesn't make sense to use:" + AUTO_DETECT_PROJECTOR + " with:" + reservationStyle);
		}
		if (identifierName.equals(FIRST_AVAILABLE_PORT) || 
			identifierName.equals(AUTO_DETECT_3D_FIRMWARE) || 
			identifierName.equals(AUTO_DETECT_PROJECTOR)) {
			identifier = null;
			ArrayList<CommPortIdentifier> identifiers = new ArrayList<CommPortIdentifier>(Collections.list(CommPortIdentifier.getPortIdentifiers()));
			for (CommPortIdentifier currentIdentifier : identifiers) {
				SerialCommunicationsPort check = getSerialDevice(currentIdentifier.getName());
				newComPortSettings.setPortName(check.getName());
				
				if (!printersBySerialPort.containsKey(check)) {
					if (identifierName.equals(FIRST_AVAILABLE_PORT)) {
						identifier = check;
						break;
					}
					
					if (identifierName.equals(AUTO_DETECT_3D_FIRMWARE) && is3dFirmware(check, newComPortSettings)) {
						identifier = check;
						break;
					}
					
					if (identifierName.equals(AUTO_DETECT_PROJECTOR)) {
						ProjectorModel model = getProjectorModel(check, newComPortSettings);
						if (model != null) {
							identifier = check;
							resources.model = model;
							break;
						}
					}
				}
			}
			
			if (identifier == null) {
				newComPortSettings.setPortName(identifierName);
				throw new InappropriateDeviceException("No serial ports found for:" + identifierName);
			}
		} 
		
		//This is a bit confusing, but if we are a projector and they chose their port directly, or chose FIRST_AVAILABLE_PORT, we haven't yet detected their projector model
		if (reservationStyle == ComPortReservation.Projector && !identifierName.equals(AUTO_DETECT_PROJECTOR)) {
			resources.model = getProjectorModel(identifier, newComPortSettings);
		}

		Printer otherPrintJob = printersBySerialPort.putIfAbsent(identifier, printer);
		if (otherPrintJob != null) {
			throw new AlreadyAssignedException("SerialPort already assigned to this job:" + otherPrintJob, otherPrintJob);
		}
		resources.comPort = identifier;
		return resources;
	}
	
	public void assignSerialPortToProjector(Printer printer, SerialCommunicationsPort identifier) throws AlreadyAssignedException, InappropriateDeviceException {
		ComPortSettings settings = printer.getConfiguration().getMachineConfig().getMonitorDriverConfig().getComPortSettings();
		if (settings == null) {
			return;
		}
		
		ComPortSettings newComPortSettings = new ComPortSettings(settings);
		DetectedResources resources = detectResourcesAndAssignPort(printer, identifier, newComPortSettings, ComPortReservation.Projector);
		identifier = resources.comPort;
		
		SerialCommunicationsPort otherPort = printer.getProjectorSerialPort();
		if (otherPort != null) {
			printersBySerialPort.remove(resources.comPort);
			throw new AlreadyAssignedException("Printer projector serial port already assigned:" + otherPort, otherPort);
		}

		if (resources.model == null) {
			printersBySerialPort.remove(resources.comPort);
			throw new InappropriateDeviceException("Couldn't determine model of projector on port:" + identifier);
		}
		
		identifier.open(printer.getName(), TIME_OUT, newComPortSettings);
		printer.setProjectorSerialPort(identifier);
		printer.setProjectorModel(resources.model);
	}
	
	public void assignSerialPortToFirmware(Printer printer, SerialCommunicationsPort identifier) throws AlreadyAssignedException, InappropriateDeviceException {
		ComPortSettings newComPortSettings = new ComPortSettings(printer.getConfiguration().getMachineConfig().getMotorsDriverConfig().getComPortSettings());
		
		DetectedResources resources = detectResourcesAndAssignPort(printer, identifier, newComPortSettings, ComPortReservation.PrinterFirmware);
		identifier = resources.comPort;
		
		SerialCommunicationsPort otherPort = printer.getPrinterFirmwareSerialPort();
		if (otherPort != null) {
			printersBySerialPort.remove(identifier);
			throw new AlreadyAssignedException("Printer firmware serial port already assigned:" + otherPort, otherPort);
		}

		identifier.open(printer.getName(), TIME_OUT, newComPortSettings);
		printer.setPrinterFirmwareSerialPort(identifier);
	}
	
	public SerialCommunicationsPort getSerialDevice(String comport) throws InappropriateDeviceException {
		// if the rxtx has problems, it will fail in getSerial
		// problem found in win 7 rxtx no class def found: io.gnu.rxtx... something like that in getSerialDevices - CommPortIdentifier.getPortIdentifiers()
		// this will start
		
		// this line shortcuts the problem for my testing.
		if("Console Testing".equalsIgnoreCase(comport)){
			return new ConsoleCommPort();
		}
		for (SerialCommunicationsPort current : getSerialDevices()) {
			if  (current.getName().equals(comport)) {
				return current;
			}
		}
		
		if (comport.equals(FIRST_AVAILABLE_PORT)) {
			return new CustomCommPort(FIRST_AVAILABLE_PORT);
		}
		
		if (comport.equals(AUTO_DETECT_3D_FIRMWARE)) {
			return new CustomCommPort(AUTO_DETECT_3D_FIRMWARE);
		}
		
		if (comport.equals(AUTO_DETECT_PROJECTOR)) {
			return new CustomCommPort(AUTO_DETECT_PROJECTOR);
		}
		
		throw new InappropriateDeviceException("CommPort doesn't exist:" + comport);
	}
	
	public List<SerialCommunicationsPort> getSerialDevices() {
		List<SerialCommunicationsPort> idents = new ArrayList<SerialCommunicationsPort>();
		Enumeration<CommPortIdentifier> identifiers = CommPortIdentifier.getPortIdentifiers();
		while (identifiers.hasMoreElements()) {
			CommPortIdentifier identifier = identifiers.nextElement();
			if (identifier.getPortType() == CommPortIdentifier.PORT_SERIAL) {
				Class<SerialCommunicationsPort> communicationsClass = HostProperties.Instance().getSerialCommunicationsClass();
				SerialCommunicationsPort comPortInstance;
				try {
					comPortInstance = communicationsClass.newInstance();
					comPortInstance.setName(identifier.getName());
					idents.add(comPortInstance);
				} catch (InstantiationException | IllegalAccessException e) {
					e.printStackTrace();
				}
			}
		}
		
		idents.add(new CustomCommPort(FIRST_AVAILABLE_PORT));
		idents.add(new CustomCommPort(AUTO_DETECT_3D_FIRMWARE));
		idents.add(new CustomCommPort(AUTO_DETECT_PROJECTOR));
		
		if (HostProperties.Instance().getFakeSerial()) {
			ConsoleCommPort consolePort = new ConsoleCommPort();
			idents.add(consolePort);
		}
		
		return idents;
	}
	
	/**
	 * This should be called when you stop using the port.
	 * This will prevent port locking on platforms like Linux.
	 */
	public void removeAssignments(Printer printer) {
		if (printer == null)
			return;
		
		SerialCommunicationsPort firmwarePort = printer.getPrinterFirmwareSerialPort();
		SerialCommunicationsPort projectorPort = printer.getProjectorSerialPort();
		
		if (firmwarePort != null) {
			printersBySerialPort.remove(firmwarePort);
			printer.setPrinterFirmwareSerialPort(null);
		}
		if (projectorPort != null) {
			printersBySerialPort.remove(projectorPort);
			printer.setProjectorSerialPort(null);
		}
		printer.setProjectorModel(null);
	}
}