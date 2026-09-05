package com.cyssoftware.effyFlow.exception;

import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice
public class GlobalExceptionHandler {

 /**
  * Captura validaciones de negocio (ej. "Folio ya existe").
  */
 @ExceptionHandler(IllegalArgumentException.class)
 public ResponseEntity<String> handleIllegalArgument(IllegalArgumentException ex) {
     return new ResponseEntity<>(ex.getMessage(), HttpStatus.BAD_REQUEST); // 400
 }

 /**
  * Captura búsquedas fallidas (ej. "Cliente no encontrado o inactivo").
  */
 @ExceptionHandler(EntityNotFoundException.class)
 public ResponseEntity<String> handleEntityNotFound(EntityNotFoundException ex) {
     return new ResponseEntity<>(ex.getMessage(), HttpStatus.NOT_FOUND); // 404
 }
}
