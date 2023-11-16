package de.dnpm.dip.connector



object HttpMethod extends Enumeration
{
  type HttpMethod = Value

  val GET,    
      PATCH,
      POST,
      PUT,
      DELETE,
      HEAD, 
      OPTIONS = Value
}
