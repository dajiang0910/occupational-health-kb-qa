package com.ohkb.api.controller;

import com.ohkb.core.ticket.SupportTicket;
import com.ohkb.core.ticket.TicketService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 工单 REST API。
 */
@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private final TicketService ticketService;

    public TicketController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    @GetMapping
    public ResponseEntity<List<SupportTicket>> listTickets(
            @RequestParam(value = "status", required = false) String status) {
        if ("pending".equals(status)) {
            return ResponseEntity.ok(ticketService.getPendingTickets());
        }
        return ResponseEntity.ok(List.of());
    }

    @GetMapping("/{id}")
    public ResponseEntity<SupportTicket> getTicket(@PathVariable Long id) {
        return ResponseEntity.ok(ticketService.getTicket(id));
    }

    @PutMapping("/{id}/claim")
    public ResponseEntity<SupportTicket> claimTicket(
            @PathVariable Long id, @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(ticketService.claimTicket(id, body.get("assignedTo")));
    }

    @PutMapping("/{id}/resolve")
    public ResponseEntity<SupportTicket> resolveTicket(
            @PathVariable Long id, @RequestBody ResolveRequest request) {
        return ResponseEntity.ok(ticketService.resolveTicket(
                id, request.resolutionNote(), request.knowledgeArticleId()));
    }

    public record ResolveRequest(String resolutionNote, Long knowledgeArticleId) {}
}
