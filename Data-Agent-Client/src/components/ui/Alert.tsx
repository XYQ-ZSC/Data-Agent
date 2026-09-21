import * as React from "react";
import { cn } from "../../lib/utils";
import { AlertCircle, XCircle, CheckCircle } from "lucide-react";

interface AlertProps extends React.HTMLAttributes<HTMLDivElement> {
    variant?: "default" | "destructive" | "success";
}

export function Alert({ className, variant = "default", children, ...props }: AlertProps) {
    return (
        <div
            role="alert"
            className={cn(
                "relative w-full rounded-lg border p-4 flex items-center gap-3",
                variant === "destructive" && "border-destructive/50 text-destructive dark:border-destructive bg-destructive/10",
                variant === "success" && "border-green-500/50 text-green-600 dark:text-green-400 dark:border-green-500 bg-green-500/10",
                variant === "default" && "bg-background text-foreground",
                className
            )}
            {...props}
        >
            {variant === "destructive" && <XCircle className="h-4 w-4 shrink-0" />}
            {variant === "success" && <CheckCircle className="h-4 w-4 shrink-0" />}
            {variant === "default" && <AlertCircle className="h-4 w-4 shrink-0" />}
            <div className="text-sm font-medium leading-none tracking-tight">
                {children}
            </div>
        </div>
    );
}
