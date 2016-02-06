package info.mineshafter.mod;

import java.io.File;
import java.io.OutputStream;
import java.util.Collection;

public class JarPatcher {
	public JarPatcher(File jarFile) {}

	public void setEntry(String name, byte[] data) {}

	public void removeEntry(String name) {}

	public Collection<String> getEntries() {
		return null;
	}

	public void write(File destination) {}

	public void write(OutputStream destination) {}
}
