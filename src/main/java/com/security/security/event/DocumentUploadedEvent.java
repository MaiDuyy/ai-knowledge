package com.security.security.event;

import com.security.security.entity.Document;
import lombok.Getter;
import lombok.Setter;
import org.springframework.context.ApplicationEvent;

@Getter
@Setter
public class DocumentUploadedEvent extends ApplicationEvent {

    private final Document document;

    public DocumentUploadedEvent(Object source, Document document) {
        super(source);
        this.document = document;
    }

}
