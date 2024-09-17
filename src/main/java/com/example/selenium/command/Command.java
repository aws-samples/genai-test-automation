package com.example.selenium.command;

//interface that implements the command pattern
public interface Command {

    public abstract Command execute() throws Exception;

    public abstract void tearDown() throws Exception;

    public abstract Command andThen(Command c) throws Exception;

    /**
     * Check the status of the test case after it finished executing the the command or chain of commands.
     * @return SUCCEED or FAIL
     * @throws Exception
     */
    public abstract String status() throws Exception;

}
