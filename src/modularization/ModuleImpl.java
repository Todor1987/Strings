package modularization;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.util.Properties;

import parallelization.CallbackReceiver;

public abstract class ModuleImpl implements Module {
	
	public static final String PROPERTYKEY_NAME = "name";
	private String name;
	private PipedInputStream inputStream = new PipedInputStream();
	private PipedOutputStream outputStream = new PipedOutputStream();
	private PipedReader inputReader = new PipedReader();
	private PipedWriter outputWriter = new PipedWriter();
	private Properties properties = new Properties();
	private CallbackReceiver callbackReceiver;

	@Override
	public PipedReader getInputReader() throws NotSupportedException {
		if (inputReader == null)
			throw new NotSupportedException("This module does not support input via reader.");
		return inputReader;
	}
	
	@Override
	public PipedWriter getOutputWriter() throws NotSupportedException {
		if (outputWriter == null)
			throw new NotSupportedException("This module does not support output via writer.");
		return outputWriter;
	}

	@Override
	public PipedInputStream getInputStream() throws NotSupportedException {
		if (inputStream == null)
			throw new NotSupportedException("This module does not support input via stream.");
		return inputStream;
	}

	@Override
	public PipedOutputStream getOutputStream() throws NotSupportedException {
		if (outputStream == null)
			throw new NotSupportedException("This module does not support output via stream.");
		return outputStream;
	}

	@Override
	public boolean process() throws Exception {
		// Has to be implemented/overridden within the subclasses
		return false;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public void setName(String name) {
		this.name = name;
		this.updateProperties();
	}

	
	@Override
	public Properties getProperties() {
		return properties;
	}

	@Override
	public void setProperties(Properties properties) throws Exception {
		if (properties==null)
			throw new Exception(this.getClass().getSimpleName()+" cannot handle null value as properties, sorry.");
		this.applyProperties();
	}
	
	/**
	 * Applies all relevant properties to this instance. Subclasses should override this,
	 * apply the properties they use themselves and call super().applyProperties()
	 * afterwards.
	 * @throws Exception when something goes wrong (property cannot be applied etc.)
	 */
	protected void applyProperties() throws Exception {
		if (this.getProperties().containsKey(PROPERTYKEY_NAME))
			this.name = this.getProperties().getProperty(PROPERTYKEY_NAME);
	}
	
	/**
	 * Enters all relevant variables into the instance's property object. Subclasses should override this,
	 * enter the properties they use themselves and call super().updateProperties()
	 * afterwards.
	 */
	protected void updateProperties(){
		this.getProperties().setProperty(PROPERTYKEY_NAME, name);
	}

	/**
	 * @param inputStream the inputStream to set
	 */
	protected void setInputStream(PipedInputStream inputStream) {
		this.inputStream = inputStream;
	}

	/**
	 * @param outputStream the outputStream to set
	 */
	protected void setOutputStream(PipedOutputStream outputStream) {
		this.outputStream = outputStream;
	}

	/**
	 * @param inputReader the inputReader to set
	 */
	protected void setInputReader(PipedReader inputReader) {
		this.inputReader = inputReader;
	}

	/**
	 * @param outputWriter the outputWriter to set
	 */
	protected void setOutputWriter(PipedWriter outputWriter) {
		this.outputWriter = outputWriter;
	}

	/* (non-Javadoc)
	 * @see parallelization.CallbackProcess#getRueckmeldungsEmpfaenger()
	 */
	@Override
	public CallbackReceiver getRueckmeldungsEmpfaenger() {
		return callbackReceiver;
	}

	/* (non-Javadoc)
	 * @see parallelization.CallbackProcess#setRueckmeldungsEmpfaenger(parallelization.CallbackReceiver)
	 */
	@Override
	public void setRueckmeldungsEmpfaenger(CallbackReceiver callbackReceiver) {
		this.callbackReceiver = callbackReceiver;
	}

	/* (non-Javadoc)
	 * @see java.lang.Runnable#run()
	 */
	@Override
	public void run() {
		try {
			Boolean result = this.process();
			this.callbackReceiver.receiveCallback(result, this);
		} catch (Exception e){
			this.callbackReceiver.receiveException(this, e);
		}
	}

	

}
