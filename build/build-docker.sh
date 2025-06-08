#!/bin/bash

# Maven Tools MCP Server - Simplified Docker Build Script
# Focuses on buildpacks with Docker Hub fallback

set -e

# Check for command line argument (for CI use)
if [ "$1" != "" ]; then
    choice="$1"
    echo "Running in non-interactive mode with option: $choice"
else
    # Interactive mode
    echo "Maven Tools MCP Server - Docker Build Options"
    echo "=============================================="

    # Check if Docker is available
    if ! command -v docker &> /dev/null; then
        echo "❌ Docker is not installed or not in PATH"
        exit 1
    fi

    echo ""
    echo "Available options:"
    echo "1. Use pre-built image from Docker Hub (fastest, recommended)"
    echo "2. Build locally with Spring Boot buildpacks (requires Maven)"
    echo ""

    read -p "Choose option (1-2): " choice
fi

case $choice in
    1)
        echo "🐳 Pulling pre-built image from Docker Hub..."
        docker pull arvindand/maven-tools-mcp:latest
        echo "✅ Ready to use: docker run -i -e SPRING_PROFILES_ACTIVE=docker arvindand/maven-tools-mcp:latest"
        ;;
    2)
        echo "🏗️  Building with Spring Boot buildpacks..."
        echo "This creates optimized, layered images using Cloud Native Buildpacks"
        
        # Check if Maven wrapper is available
        if [ ! -f "../mvnw" ]; then
            echo "❌ Maven wrapper not found. Run this script from the build/ directory."
            exit 1
        fi
        
        # Make mvnw executable if it isn't already
        chmod +x ../mvnw
        
        echo "📦 Running Maven package (skipping tests for faster build)..."
        ../mvnw clean package -DskipTests
        
        echo "🐳 Building Docker image with buildpacks..."
        ../mvnw spring-boot:build-image
        
        PROJECT_VERSION=$(../mvnw help:evaluate -Dexpression=project.version -q -DforceStdout 2>/dev/null || echo "0.1.2-SNAPSHOT")
        echo "✅ Built image: maven-tools-mcp:${PROJECT_VERSION}"
        echo "🚀 Run with: docker run -i -e SPRING_PROFILES_ACTIVE=docker maven-tools-mcp:${PROJECT_VERSION}"
        ;;
    *)
        echo "❌ Invalid option. Please choose 1 or 2."
        exit 1
        ;;
esac

echo ""
echo "🎉 Docker setup complete!"
