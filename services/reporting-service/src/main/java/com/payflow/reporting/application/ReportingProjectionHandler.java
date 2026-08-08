package com.payflow.reporting.application;
import com.payflow.reporting.application.port.ProjectionStore;
import java.util.Objects;
import org.springframework.stereotype.Service;
@Service
public final class ReportingProjectionHandler{
 private final ProjectionEventParser parser;private final ProjectionStore store;
 public ReportingProjectionHandler(ProjectionEventParser parser,ProjectionStore store){this.parser=parser;this.store=store;}
 public boolean handle(String key,String payload){return store.appendAndProject(parser.parse(key,payload));}
}
