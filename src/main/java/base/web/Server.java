package base.web;

import static spark.Spark.*;

import java.io.InputStream;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.logging.LogManager;
import java.util.stream.Collectors;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.ParseException;
import org.apache.log4j.PropertyConfigurator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.Expose;

import base.web.WebError.InvalidInputException;
import base.web.WebError.ResourceNotFoundException;
import base.workbench.ModuleWorkbenchController;
import modules.Module;
import spark.ExceptionHandler;
import spark.Request;

public class Server {

	private static final Logger LOGGER = LoggerFactory.getLogger(Server.class);

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().excludeFieldsWithoutExposeAnnotation()
			.create();

	/**
	 * A simple message class to communicate strings (mostly errors) to the outside
	 * world.
	 */
	private static class Message {
		@Expose
		String message;

		public Message(String message) {
			this.message = message;
		}
	}

	protected static ExceptionHandler<Exception> invalidInputHandler = (e, request, response) -> {
		response.status(400);
		Message msg = new Message("Invalid input: " + e.getMessage());
		response.body(GSON.toJson(msg));
	};

	protected static ExceptionHandler<Exception> resourceNotFoundHandler = (e, request, response) -> {
		response.status(404);
		Message msg = new Message("Resource not found: " + e.getMessage());
		response.body(GSON.toJson(msg));
	};

	protected static ExceptionHandler<Exception> internalServerErrorHandler = (e, request, response) -> {
		response.status(500);
		Message msg = new Message("Internal Server error: " + e.getMessage());
		response.body(GSON.toJson(msg));
	};

	public static void main(String[] args) {

		// initialise the log4j properties for the web server manually
		InputStream log4jProps = Server.class.getClassLoader().getResourceAsStream("web/log4j.properties");
		if (log4jProps == null) {
			System.out.println("Refusing startup: Could not find logging properties.");
			return;
		} else {
			PropertyConfigurator.configure(log4jProps);
		}

		// parse the command line arguments and the webserver properties file
		CommandLineParser parser = new DefaultParser();
		try {
			CommandLine cl = parser.parse(ServerConfig.CLI_OPTIONS, args);
			if (cl.hasOption("help")) {
				(new HelpFormatter()).printHelp("java -jar modulewebserver.jar", ServerConfig.CLI_OPTIONS);
				System.exit(0);
			} else {
				// hand both the properties file path and the command line options to the
				// ServerConfig to let it decide on the final values
				ServerConfig.initialize("web/webserver.properties", cl);
			}
		} catch (ParseException e) {
			LOGGER.error("Error while parsing the server configuration: " + e.getMessage());
			e.printStackTrace();
		}

		// Disable logging from the root logger used in the rest of the workbench
		// (Error messages will be persisted to the database and exposed to the client
		// via the API)
		if (ServerConfig.get().shouldShutdownWorkbenchLogger()) {
			LOGGER.info("Shutting down the workbench root logger.");
			LogManager.getLogManager().reset();
		}

		// Configure the port to run spark on
		Integer port = ServerConfig.get().getPort();
		if (port != null && port > 0) {
			port(ServerConfig.get().getPort());
		} else {
			LOGGER.error("Shutting down server. Invalid port number: " + port);
			stop();
		}

		// output some status information as configuration is now complete
		LOGGER.debug(String.format("Running instance '%s' on port '%d'", ServerConfig.get().getName(),
				ServerConfig.get().getPort()));

		// start the job scheduler alongside this server
		Thread jobSchedulerThread = new Thread(JobScheduler.instance());
		jobSchedulerThread.start();

		post("jobs", (request, response) -> {

			Job job = (Job) GSON.fromJson(request.body(), Job.class);

			if (JobDao.exists(job.getId())) {
				throw new WebError.InvalidJobDefinition("A job for this id already exists: " + job.getId());
			}

			// test that the workflow definition is parseable
			ModuleWorkbenchController controller = new ModuleWorkbenchController();
			try {
				controller.loadModuleNetworkFromString(job.getWorkflowDefinition(), false);
			} catch (Exception e) {
				throw new WebError.InvalidWorkflowDefiniton(e);
			}

			job.save();
			JobScheduler.instance().wakeup();
			return job;

		}, GSON::toJson);

		get("jobs/:id", (request, response) -> {
			return getJobByRequestIdParam(request, ":id");
		}, GSON::toJson);

		post("jobs/:id/cancel", (request, response) -> {
			Job job = getJobByRequestIdParam(request, ":id");

			if (job.hasEnded()) {
				throw new InvalidInputException("Jobs has already ended.");
			} else {
				job.setFailed("Manually cancelled");
				JobScheduler.instance().wakeup();
			}
			return "";
		}, GSON::toJson);

		get("health", (request, response) -> {
			return HealthInformation.collect();
		}, GSON::toJson);

		get("modules"/* , "application/json" */, (request, response) -> {

			ModuleWorkbenchController controller = new ModuleWorkbenchController();
			Map<String, Module> modules = controller.getAvailableModules();

			List<ModuleProfile> profiles = modules.values().stream().map((Module m) -> new ModuleProfile(m))
					.collect(Collectors.toList());

			response.type("application/json");
			if (profiles == null || profiles.isEmpty()) {
				LOGGER.error("No modules could be loaded.");
				return null;
			} else {
				return profiles;
			}
		}, GSON::toJson);

		// map common exceptions in requests to our generic handlers
		exception(SQLException.class, internalServerErrorHandler);
		exception(JsonSyntaxException.class, invalidInputHandler);
		exception(WebError.InvalidInputException.class, invalidInputHandler);
		exception(WebError.InvalidJobDefinition.class, invalidInputHandler);
		exception(WebError.InvalidWorkflowDefiniton.class, invalidInputHandler);
		exception(WebError.ResourceNotFoundException.class, resourceNotFoundHandler);
	}

	private static Job getJobByRequestIdParam(Request request, String param)
			throws SQLException, InvalidInputException, ResourceNotFoundException {
		long id = -1;
		try {
			id = Long.parseLong(request.params(param));
			Job job = JobDao.fetch(id);
			if (job == null) {
				throw new ResourceNotFoundException("No job for id: " + id);
			}
			return job;
		} catch (NumberFormatException e) {
			throw new InvalidInputException("No parseable id param given.");
		}
	}
}