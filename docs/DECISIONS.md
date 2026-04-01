# Paralife — Architecture Decisions

## D001: Client-Server Connectivity → WebSocket
**Made by:** collaborative  
**Rationale:** Persistent connections allow server-pushed tick events, natural fit for real-time simulation. Showcases Spring WebSocket skills relevant to Canva's real-time collaboration architecture.  
**Revisable:** Yes

## D002: Concurrency Model → Virtual Threads (Java 21+, Project Loom)
**Made by:** collaborative  
**Rationale:** Modern approach that handles massive concurrency with simple blocking code. Impressive on resume, directly relevant to handling millions of concurrent users at Canva scale. Avoids reactive complexity while achieving similar throughput.  
**Revisable:** Yes

## D003: World Grid Topology → 2D Toroidal Grid
**Made by:** collaborative  
**Rationale:** Classic cellular automata structure. Easy to reason about, naturally partitionable for horizontal scaling, proven model for neighbor queries and spatial indexing.  
**Revisable:** Yes
