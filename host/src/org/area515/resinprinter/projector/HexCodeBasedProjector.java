package org.area515.resinprinter.projector;

import java.io.IOException;
import java.util.regex.Pattern;

import javax.xml.bind.DatatypeConverter;
import javax.xml.bind.annotation.XmlElement;

import org.area515.resinprinter.serial.SerialCommunicationsPort;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

public class HexCodeBasedProjector implements ProjectorModel {
	private static final int PROJECTOR_TIMEOUT = 5000;
	
	@JsonIgnore
	private byte[] onHex;
	@JsonIgnore
	private byte[] offHex;
	@JsonIgnore
	private byte[] detectionHex;
	@JsonIgnore
	private Pattern detectionResponsePattern;
	@JsonIgnore
	private String name;
		
	public HexCodeBasedProjector() {
	}

	@JsonProperty
	public String getOnHex() {
		return DatatypeConverter.printHexBinary(onHex);
	}
	public void setOnHex(String onHex) {
		this.onHex = DatatypeConverter.parseHexBinary(onHex);
	}

	@JsonProperty
	public String getOffHex() {
		return DatatypeConverter.printHexBinary(offHex);
	}
	public void setOffHex(String offHex) {
		this.offHex = DatatypeConverter.parseHexBinary(offHex);
	}
	
	@JsonProperty
	public String getName() {
		return name;
	}
	public void setName(String name) {
		this.name = name;
	}
	
	@JsonProperty
	public String getDetectionHex() {
		return DatatypeConverter.printHexBinary(detectionHex);
	}
	public void setDetectionHex(String detectionHex) {
		this.detectionHex = DatatypeConverter.parseHexBinary(detectionHex);
	}

	@JsonProperty
	public String getDetectionResponseRegex() {
		return detectionResponsePattern.pattern();
	}
	public void setDetectionResponseRegex(String detectionResponsePattern) {
		this.detectionResponsePattern = Pattern.compile(detectionResponsePattern);
	}

	@Override
	public boolean autodetect(SerialCommunicationsPort port) {
		StringBuilder builder = new StringBuilder();
		try {
			port.write(detectionHex);
			long start = System.currentTimeMillis();
			while (true) {
				byte[] response = port.read();
				if (response != null) {
					builder.append(new String(response));
					if (detectionResponsePattern.matcher(builder.toString()).matches()) {
						return true;
					}
				}
				
				if (System.currentTimeMillis() - start >= PROJECTOR_TIMEOUT) {
					System.out.println("Timeout after bytes read \"" + DatatypeConverter.printHexBinary(builder.toString().getBytes()) + "\"");
					return false;
				}
			}
		} catch (IOException e) {
			System.out.println("Error after bytes read \"" + DatatypeConverter.printHexBinary(builder.toString().getBytes()) + "\"");
			e.printStackTrace();
			return false;
		}
	}
	
	public String testCodeAgainstPattern(SerialCommunicationsPort port, String hexCode) throws IOException {
		System.out.println("Writing:" + hexCode);
		port.write(DatatypeConverter.parseHexBinary(hexCode));
		long start = System.currentTimeMillis();
		StringBuilder builder = new StringBuilder();
		while (true) {
			byte[] response = port.read();
			if (response != null) {
				builder.append(new String(response));
				
				if (detectionResponsePattern.matcher(builder.toString()).matches()) {
					return "Match:(" + DatatypeConverter.printHexBinary(builder.toString().getBytes()) + ") against: " + detectionResponsePattern.pattern();
				}
			}
			
			if (System.currentTimeMillis() - start >= PROJECTOR_TIMEOUT) {
				return "No Match:(" + DatatypeConverter.printHexBinary(builder.toString().getBytes()) + ") against: " + detectionResponsePattern.pattern();
			}
		}
	}

	@Override
	public void setPowerState(boolean state, SerialCommunicationsPort port) throws IOException {
		if (state) {
			port.write(onHex);
		} else {
			port.write(offHex);
		}
	}

	@Override
	public boolean getPowerState(SerialCommunicationsPort port) throws IOException {
		throw new IOException("This feature isn't implemented yet");
	}

	public String toString() {
		return name;
	}
}
