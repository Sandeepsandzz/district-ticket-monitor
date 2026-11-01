package com.district.monitor.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name="alert_deliveries", indexes={@Index(name="idx_delivery_event", columnList="eventId")})
public class AlertDelivery {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false) private Long eventId;
    @Column(nullable=false, length=32) private String channel;
    @Column(nullable=false, length=20) private String status="PENDING";
    @Column(nullable=false) private int attempts;
    @Column private Instant lastAttemptAt;
    @Column private Instant nextAttemptAt;
    @Column(length=300) private String errorMessage;
    public AlertDelivery() {}
    public AlertDelivery(Long eventId,String channel) { this.eventId=eventId; this.channel=channel; }
    public Long getId(){return id;} public Long getEventId(){return eventId;} public String getChannel(){return channel;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public int getAttempts(){return attempts;} public void setAttempts(int v){attempts=v;}
    public Instant getLastAttemptAt(){return lastAttemptAt;} public void setLastAttemptAt(Instant v){lastAttemptAt=v;}
    public Instant getNextAttemptAt(){return nextAttemptAt;} public void setNextAttemptAt(Instant v){nextAttemptAt=v;}
    public String getErrorMessage(){return errorMessage;} public void setErrorMessage(String v){errorMessage=v;}
}
